//! A lossless, owned copy of the KDBX XML payload.
//!
//! Every element, attribute and text node the file contains is kept, so writing the tree back
//! changes only what an edit changed: history, times, icons, custom data, auto-type and fields
//! this crate doesn't model all survive. Protected values (`<Value Protected="True">`) hold the
//! decrypted text as a [`SecretString`] and are re-encrypted with a fresh inner stream on save.

use std::fmt::{self, Write as _};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use roxmltree::{Document, ParsingOptions};
use zeroize::{Zeroize, Zeroizing};

use super::crypto::ProtectedStream;
use crate::error::{Limit, OpenError};
use crate::limits::OpenLimits;
use crate::secret::SecretString;

const ERR: OpenError = OpenError::XmlInvalid;

pub(crate) enum Node {
    Element(Element),
    Text(Zeroizing<String>),
    Secret(SecretString),
}

pub(crate) struct Element {
    pub(crate) name: String,
    pub(crate) attributes: Vec<(String, String)>,
    pub(crate) children: Vec<Node>,
}

impl Element {
    pub(crate) fn new(name: &str) -> Self {
        Self {
            name: name.to_owned(),
            attributes: Vec::new(),
            children: Vec::new(),
        }
    }

    pub(crate) fn with_text(name: &str, text: impl Into<String>) -> Self {
        let mut element = Self::new(name);
        element.set_text(text);
        element
    }

    pub(crate) fn with_child(mut self, child: Element) -> Self {
        self.children.push(Node::Element(child));
        self
    }

    pub(crate) fn attribute(&self, name: &str) -> Option<&str> {
        self.attributes
            .iter()
            .find(|(key, _)| key == name)
            .map(|(_, value)| value.as_str())
    }

    pub(crate) fn set_attribute(&mut self, name: &str, value: &str) {
        match self.attributes.iter_mut().find(|(key, _)| key == name) {
            Some(slot) => value.clone_into(&mut slot.1),
            None => self.attributes.push((name.to_owned(), value.to_owned())),
        }
    }

    pub(crate) fn remove_attribute(&mut self, name: &str) {
        self.attributes.retain(|(key, _)| key != name);
    }

    pub(crate) fn elements(&self) -> impl Iterator<Item = &Element> {
        self.children.iter().filter_map(|node| match node {
            Node::Element(element) => Some(element),
            _ => None,
        })
    }

    pub(crate) fn elements_mut(&mut self) -> impl Iterator<Item = &mut Element> {
        self.children.iter_mut().filter_map(|node| match node {
            Node::Element(element) => Some(element),
            _ => None,
        })
    }

    pub(crate) fn child(&self, name: &str) -> Option<&Element> {
        self.elements().find(|e| e.name == name)
    }

    pub(crate) fn child_mut(&mut self, name: &str) -> Option<&mut Element> {
        self.elements_mut().find(|e| e.name == name)
    }

    /// The named child, appended if missing.
    pub(crate) fn ensure_child(&mut self, name: &str) -> &mut Element {
        if self.child(name).is_none() {
            self.children.push(Node::Element(Element::new(name)));
        }
        self.child_mut(name).expect("just ensured")
    }

    /// Concatenated plain text. A protected value is not plain text and reads as `None`.
    pub(crate) fn text(&self) -> Option<String> {
        let mut out = String::new();
        for node in &self.children {
            match node {
                Node::Text(text) => out.push_str(text),
                Node::Secret(_) => return None,
                Node::Element(_) => {}
            }
        }
        Some(out)
    }

    pub(crate) fn secret(&self) -> Option<&SecretString> {
        self.children.iter().find_map(|node| match node {
            Node::Secret(secret) => Some(secret),
            _ => None,
        })
    }

    pub(crate) fn is_protected(&self) -> bool {
        self.attribute("Protected")
            .is_some_and(|value| value.eq_ignore_ascii_case("true"))
    }

    pub(crate) fn set_text(&mut self, text: impl Into<String>) {
        self.children.clear();
        let text = text.into();
        if !text.is_empty() {
            self.children.push(Node::Text(Zeroizing::new(text)));
        }
    }

    pub(crate) fn set_secret(&mut self, secret: SecretString) {
        self.children.clear();
        self.children.push(Node::Secret(secret));
    }

    pub(crate) fn remove_children(
        &mut self,
        mut predicate: impl FnMut(&Element) -> bool,
    ) -> Vec<Element> {
        let mut removed = Vec::new();
        let mut kept = Vec::with_capacity(self.children.len());
        for node in self.children.drain(..) {
            match node {
                Node::Element(element) if predicate(&element) => removed.push(element),
                other => kept.push(other),
            }
        }
        self.children = kept;
        if !removed.is_empty() {
            self.merge_adjacent_text();
        }
        removed
    }

    /// Joins text nodes that now sit side by side. A file written with indentation has whitespace
    /// between its elements; taking an element out leaves the whitespace before and after it as two
    /// nodes, which the written file cannot tell apart from one. Keeping them as one keeps the tree
    /// the same as what is read back.
    fn merge_adjacent_text(&mut self) {
        let mut merged: Vec<Node> = Vec::with_capacity(self.children.len());
        for node in self.children.drain(..) {
            match (merged.last_mut(), node) {
                (Some(Node::Text(before)), Node::Text(after)) => {
                    let mut joined =
                        Zeroizing::new(String::with_capacity(before.len() + after.len()));
                    joined.push_str(before);
                    joined.push_str(&after);
                    *before = joined;
                }
                (_, other) => merged.push(other),
            }
        }
        self.children = merged;
    }

    /// A deep copy. Secrets are copied into new zeroizing buffers.
    pub(crate) fn duplicate(&self) -> Element {
        Element {
            name: self.name.clone(),
            attributes: self.attributes.clone(),
            children: self
                .children
                .iter()
                .map(|node| match node {
                    Node::Element(element) => Node::Element(element.duplicate()),
                    Node::Text(text) => Node::Text(text.clone()),
                    Node::Secret(secret) => {
                        Node::Secret(SecretString::new(secret.expose().to_owned()))
                    }
                })
                .collect(),
        }
    }

    /// Where two trees first differ, in words and without any vault content: element names, child
    /// positions, the kind of node, and for plain text whether only line endings or surrounding
    /// spaces differ. No title, value or other text is ever quoted. For the save check, which
    /// otherwise can only say "different".
    pub(crate) fn first_difference(&self, other: &Element) -> Option<String> {
        let mut path = Vec::new();
        self.difference(other, &mut path)
    }

    fn difference(&self, other: &Element, path: &mut Vec<String>) -> Option<String> {
        path.push(self.name.clone());
        let here = |path: &[String], what: String| Some(format!("{}: {what}", path.join("/")));
        if self.name != other.name {
            return here(
                path,
                format!("element name differs ({} vs {})", self.name, other.name),
            );
        }
        if self.attributes != other.attributes {
            let names = |e: &Element| {
                e.attributes
                    .iter()
                    .map(|(k, _)| k.clone())
                    .collect::<Vec<_>>()
            };
            return here(
                path,
                format!(
                    "attributes differ (names {:?} vs {:?})",
                    names(self),
                    names(other)
                ),
            );
        }
        if self.children.len() != other.children.len() {
            return here(
                path,
                format!(
                    "child count differs ({} vs {})",
                    self.children.len(),
                    other.children.len()
                ),
            );
        }
        for (index, pair) in self.children.iter().zip(&other.children).enumerate() {
            match pair {
                (Node::Element(a), Node::Element(b)) => {
                    path.push(format!("[{index}]"));
                    if let Some(found) = a.difference(b, path) {
                        return Some(found);
                    }
                    path.pop();
                }
                (Node::Text(a), Node::Text(b)) if a.as_str() != b.as_str() => {
                    let lines = |s: &str| s.replace("\r\n", "\n").replace('\r', "\n");
                    let how = if lines(a.as_str()) == lines(b.as_str()) {
                        "only line endings differ".to_owned()
                    } else if a.trim() == b.trim() {
                        "only surrounding whitespace differs".to_owned()
                    } else {
                        format!("content differs ({} vs {} bytes)", a.len(), b.len())
                    };
                    return here(path, format!("text {how}"));
                }
                (Node::Secret(a), Node::Secret(b)) if a.expose() != b.expose() => {
                    return here(path, "protected value differs".to_owned());
                }
                (Node::Text(_), Node::Text(_)) | (Node::Secret(_), Node::Secret(_)) => {}
                (a, b) => {
                    let kind = |n: &Node| match n {
                        Node::Element(_) => "element",
                        Node::Text(_) => "text",
                        Node::Secret(_) => "protected value",
                    };
                    return here(
                        path,
                        format!("node kind differs ({} vs {})", kind(a), kind(b)),
                    );
                }
            }
        }
        path.pop();
        None
    }

    pub(crate) fn same_as(&self, other: &Element) -> bool {
        self.name == other.name
            && self.attributes == other.attributes
            && self.children.len() == other.children.len()
            && self
                .children
                .iter()
                .zip(&other.children)
                .all(|pair| match pair {
                    (Node::Element(a), Node::Element(b)) => a.same_as(b),
                    (Node::Text(a), Node::Text(b)) => a.as_str() == b.as_str(),
                    (Node::Secret(a), Node::Secret(b)) => a.expose() == b.expose(),
                    _ => false,
                })
    }
}

impl Drop for Element {
    fn drop(&mut self) {
        for (key, value) in &mut self.attributes {
            key.zeroize();
            value.zeroize();
        }
    }
}

impl fmt::Debug for Element {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Element")
            .field("name", &self.name)
            .field("children", &self.children.len())
            .finish_non_exhaustive()
    }
}

/// Parses the payload XML. Protected values are decrypted in document order, exactly as the
/// writer encrypts them.
pub(crate) fn parse(
    xml: &[u8],
    stream: &mut ProtectedStream,
    limits: &OpenLimits,
) -> Result<Element, OpenError> {
    let text = std::str::from_utf8(xml).map_err(|_| ERR)?;
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    let options = ParsingOptions {
        allow_dtd: false,
        nodes_limit: limits.max_xml_nodes,
        ..ParsingOptions::default()
    };
    let document = Document::parse_with_options(text, options).map_err(|error| match error {
        roxmltree::Error::NodesLimitReached => OpenError::LimitExceeded(Limit::XmlNodes),
        _ => ERR,
    })?;
    let root = document.root_element();
    if root.tag_name().name() != "KeePassFile" {
        return Err(ERR);
    }
    let mut converter = Converter {
        stream,
        limits,
        max_depth: max_depth(limits),
    };
    converter.element(root, 0)
}

/// Groups nest up to `max_group_depth`, and each level adds a handful of elements.
fn max_depth(limits: &OpenLimits) -> usize {
    limits.max_group_depth.saturating_mul(2).saturating_add(64)
}

struct Converter<'a> {
    stream: &'a mut ProtectedStream,
    limits: &'a OpenLimits,
    max_depth: usize,
}

impl Converter<'_> {
    fn element(
        &mut self,
        node: roxmltree::Node<'_, '_>,
        depth: usize,
    ) -> Result<Element, OpenError> {
        if depth > self.max_depth {
            return Err(OpenError::LimitExceeded(Limit::GroupDepth));
        }
        let mut element = Element::new(node.tag_name().name());
        element.attributes = node
            .attributes()
            .map(|a| (a.name().to_owned(), a.value().to_owned()))
            .collect();
        if element.name == "Value" && element.is_protected() {
            let encoded = node.text().unwrap_or("");
            let max_encoded = self.limits.max_field_size.saturating_mul(4) / 3 + 4;
            if encoded.len() > max_encoded {
                return Err(OpenError::LimitExceeded(Limit::FieldSize));
            }
            let mut bytes = Zeroizing::new(STANDARD.decode(encoded.trim()).map_err(|_| ERR)?);
            self.stream.apply(bytes.as_mut_slice());
            let plain = std::str::from_utf8(&bytes).map_err(|_| ERR)?;
            element.set_secret(SecretString::new(plain.to_owned()));
            return Ok(element);
        }
        for child in node.children() {
            if child.is_element() {
                element
                    .children
                    .push(Node::Element(self.element(child, depth + 1)?));
            } else if child.is_text() {
                // Comments and processing instructions are dropped; the text around them joins
                // into one node, exactly as it will read back after a write.
                let text = child.text().unwrap_or("");
                if let Some(Node::Text(previous)) = element.children.last_mut() {
                    previous.push_str(text);
                } else {
                    element
                        .children
                        .push(Node::Text(Zeroizing::new(text.to_owned())));
                }
                if let Some(Node::Text(joined)) = element.children.last() {
                    if joined.len() > self.limits.max_field_size {
                        return Err(OpenError::LimitExceeded(Limit::FieldSize));
                    }
                }
            }
        }
        Ok(element)
    }
}

/// Serializes the tree as UTF-8 XML, encrypting protected values with `stream` in document order.
pub(crate) fn write(root: &Element, stream: &mut ProtectedStream) -> Zeroizing<Vec<u8>> {
    let mut out = Zeroizing::new(String::with_capacity(64 * 1024));
    out.push_str("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n");
    write_element(root, stream, &mut out);
    Zeroizing::new(out.as_bytes().to_vec())
}

fn write_element(element: &Element, stream: &mut ProtectedStream, out: &mut String) {
    out.push('<');
    out.push_str(&element.name);
    for (key, value) in &element.attributes {
        let _ = write!(out, " {key}=\"");
        escape(value, true, out);
        out.push('"');
    }
    if element.children.is_empty() {
        out.push_str("/>");
        return;
    }
    out.push('>');
    for child in &element.children {
        match child {
            Node::Element(child) => write_element(child, stream, out),
            Node::Text(text) => escape(text, false, out),
            Node::Secret(secret) => {
                let mut bytes = Zeroizing::new(secret.expose().as_bytes().to_vec());
                stream.apply(bytes.as_mut_slice());
                out.push_str(&STANDARD.encode(bytes.as_slice()));
            }
        }
    }
    out.push_str("</");
    out.push_str(&element.name);
    out.push('>');
}

fn escape(text: &str, attribute: bool, out: &mut String) {
    for c in text.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' if attribute => out.push_str("&quot;"),
            '\r' => out.push_str("&#13;"),
            '\t' if attribute => out.push_str("&#9;"),
            '\n' if attribute => out.push_str("&#10;"),
            _ => out.push(c),
        }
    }
}

/// XML 1.0 can't carry most control characters, even escaped.
pub(crate) fn is_xml_safe(text: &str) -> bool {
    text.chars().all(|c| {
        matches!(c, '\t' | '\n' | '\r')
            || ('\u{20}'..='\u{D7FF}').contains(&c)
            || ('\u{E000}'..='\u{FFFD}').contains(&c)
            || c >= '\u{10000}'
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse_str(xml: &str) -> Element {
        parse(
            xml.as_bytes(),
            &mut ProtectedStream::none(),
            &OpenLimits::default(),
        )
        .unwrap()
    }

    #[test]
    fn the_first_difference_is_named_without_values() {
        let entry = |notes: &str| {
            parse_str(&format!(
                "<KeePassFile><Root><Entry><String><Key>Title</Key><Value>Docker</Value></String>\
                 <String><Key>Notes</Key><Value>{notes}</Value></String></Entry></Root></KeePassFile>"
            ))
        };
        assert_eq!(entry("a").first_difference(&entry("a")), None);
        let found = entry("a b").first_difference(&entry("a  b")).unwrap();
        assert!(found.contains("Entry"), "{found}");
        assert!(found.contains("content differs"), "{found}");
        // Neither the title nor the differing text may appear in the diagnostic.
        for secret in ["Docker", "a b", "a  b"] {
            assert!(!found.contains(secret), "{found}");
        }
        let spaces = entry("x").first_difference(&entry(" x ")).unwrap();
        assert!(spaces.contains("surrounding whitespace"), "{spaces}");
    }

    #[test]
    fn removing_an_element_from_indented_xml_leaves_the_tree_a_reader_would_build() {
        let mut tree = parse_str(
            "<KeePassFile>\n\t<Entry>\n\t\t<A>1</A>\n\t\t<History>\n\t\t</History>\n\t\t<B>2</B>\n\t</Entry>\n</KeePassFile>",
        );
        let entry = tree.child_mut("Entry").unwrap();
        entry.remove_children(|e| e.name == "History");
        let written = write(&tree, &mut ProtectedStream::none());
        let again = parse(
            &written,
            &mut ProtectedStream::none(),
            &OpenLimits::default(),
        )
        .unwrap();
        assert_eq!(tree.first_difference(&again), None);
        assert!(tree.same_as(&again));
    }

    #[test]
    fn round_trips_unknown_elements_attributes_and_whitespace() {
        let xml = "<KeePassFile><Meta><X a=\"1&amp;2\">t&lt;x&gt;</X>\n\t<Empty/></Meta><Root><CustomThing k=\"v\"><Deep>1</Deep></CustomThing></Root></KeePassFile>";
        let tree = parse_str(xml);
        let written = write(&tree, &mut ProtectedStream::none());
        let again = parse(
            &written,
            &mut ProtectedStream::none(),
            &OpenLimits::default(),
        )
        .unwrap();
        assert!(tree.same_as(&again));
        let text = String::from_utf8(written.to_vec()).unwrap();
        assert!(text.contains("<X a=\"1&amp;2\">t&lt;x&gt;</X>\n\t<Empty/>"));
    }

    #[test]
    fn protected_values_round_trip_through_a_stream() {
        let key = [7u8; 64];
        let mut tree = parse_str(
            "<KeePassFile><Root><Value Protected=\"True\"></Value><Value>plain</Value><Value Protected=\"True\"></Value></Root></KeePassFile>",
        );
        {
            let root = tree.child_mut("Root").unwrap();
            let mut values = root.elements_mut();
            values
                .next()
                .unwrap()
                .set_secret(SecretString::new("hunter2".to_owned()));
            values.next();
            values
                .next()
                .unwrap()
                .set_secret(SecretString::new("ünïcode".to_owned()));
        }
        let written = write(&tree, &mut ProtectedStream::chacha20(&key).unwrap());
        let text = String::from_utf8(written.to_vec()).unwrap();
        assert!(!text.contains("hunter2") && text.contains(">plain<"));
        let again = parse(
            &written,
            &mut ProtectedStream::chacha20(&key).unwrap(),
            &OpenLimits::default(),
        )
        .unwrap();
        assert!(tree.same_as(&again));
    }

    #[test]
    fn rejects_excessive_nesting() {
        let limits = OpenLimits {
            max_group_depth: 2,
            ..OpenLimits::default()
        };
        let xml = format!(
            "<KeePassFile>{}{}</KeePassFile>",
            "<a>".repeat(80),
            "</a>".repeat(80)
        );
        assert_eq!(
            parse(xml.as_bytes(), &mut ProtectedStream::none(), &limits).unwrap_err(),
            OpenError::LimitExceeded(Limit::GroupDepth)
        );
    }

    #[test]
    fn text_split_by_a_comment_or_instruction_reads_as_one_node() {
        let tree = parse_str("<KeePassFile><X>a<!-- c -->b<?pi?>c</X></KeePassFile>");
        let x = tree.child("X").unwrap();
        assert_eq!(x.children.len(), 1);
        assert_eq!(x.text().as_deref(), Some("abc"));
        let again = parse(
            &write(&tree, &mut ProtectedStream::none()),
            &mut ProtectedStream::none(),
            &OpenLimits::default(),
        )
        .unwrap();
        assert!(tree.same_as(&again));
    }

    #[test]
    fn control_characters_are_not_xml_safe() {
        assert!(is_xml_safe("tab\tnew\nline ünï 😀"));
        assert!(!is_xml_safe("bell\u{7}"));
        assert!(!is_xml_safe("nul\u{0}"));
    }
}
