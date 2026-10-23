use base64::Engine;
use base64::engine::general_purpose::STANDARD;

use super::dom::Element;
use crate::error::{Limit, OpenError};
use crate::limits::OpenLimits;
use crate::model::{
    Attachment, EntryField, FieldValue, HistoryEntry, ItemId, VaultEntry, VaultGroup,
};
use crate::secret::SecretString;

const ERR: OpenError = OpenError::XmlInvalid;

#[derive(Debug, Default)]
pub(crate) struct XmlMetadata {
    pub(crate) name: String,
    pub(crate) description: String,
    pub(crate) generator: String,
    pub(crate) recycle_bin: Option<ItemId>,
}

/// The read model, projected from the lossless document.
pub(crate) fn project(
    document: &Element,
    attachment_sizes: &[usize],
    limits: &OpenLimits,
) -> Result<(XmlMetadata, VaultGroup), OpenError> {
    if document.name != "KeePassFile" {
        return Err(ERR);
    }
    let mut parser = Parser {
        attachment_sizes,
        limits,
        groups: 0,
        entries: 0,
    };
    let metadata = document
        .child("Meta")
        .map(parse_metadata)
        .transpose()?
        .unwrap_or_default();
    let root_group = document
        .child("Root")
        .and_then(|r| r.child("Group"))
        .ok_or(ERR)?;
    let group = parser.group(root_group, 0)?;
    Ok((metadata, group))
}

fn parse_metadata(meta: &Element) -> Result<XmlMetadata, OpenError> {
    let recycle_enabled =
        child_text(meta, "RecycleBinEnabled").is_some_and(|v| v.eq_ignore_ascii_case("true"));
    let recycle_bin = match meta.child("RecycleBinUUID") {
        Some(node) if recycle_enabled => Some(parse_uuid(node)?).filter(|id| !id.is_nil()),
        _ => None,
    };
    Ok(XmlMetadata {
        name: child_text(meta, "DatabaseName").unwrap_or_default(),
        description: child_text(meta, "DatabaseDescription").unwrap_or_default(),
        generator: child_text(meta, "Generator").unwrap_or_default(),
        recycle_bin,
    })
}

struct Parser<'a> {
    attachment_sizes: &'a [usize],
    limits: &'a OpenLimits,
    groups: usize,
    entries: usize,
}

impl Parser<'_> {
    fn group(&mut self, node: &Element, depth: usize) -> Result<VaultGroup, OpenError> {
        if depth > self.limits.max_group_depth {
            return Err(OpenError::LimitExceeded(Limit::GroupDepth));
        }
        self.groups += 1;
        if self.groups > self.limits.max_groups {
            return Err(OpenError::LimitExceeded(Limit::Groups));
        }
        let mut id = None;
        let mut group = VaultGroup {
            id: ItemId::from_bytes([0; 16]),
            name: String::new(),
            icon: crate::model::DEFAULT_GROUP_ICON,
            notes: String::new(),
            groups: Vec::new(),
            entries: Vec::new(),
        };
        for child in node.elements() {
            match child.name.as_str() {
                "UUID" => id = Some(parse_uuid(child)?),
                "Name" => group.name = plain_text(child)?,
                "IconID" => {
                    if let Some(icon) = child.text().and_then(|t| t.trim().parse().ok()) {
                        group.icon = icon;
                    }
                }
                "Notes" => group.notes = plain_text(child)?,
                "Group" => group.groups.push(self.group(child, depth + 1)?),
                "Entry" => group.entries.push(self.entry(child)?),
                _ => {}
            }
        }
        group.id = id.ok_or(ERR)?;
        Ok(group)
    }

    fn entry(&mut self, node: &Element) -> Result<VaultEntry, OpenError> {
        self.count_entry()?;
        let mut entry = self.fields(node)?;
        let versions = node
            .elements()
            .filter(|n| n.name == "History")
            .flat_map(|h| h.elements().filter(|n| n.name == "Entry"));
        for (position, version) in versions.enumerate() {
            self.count_entry()?;
            // A version that can't be read is left out; the current entry still opens.
            if let Ok(mut version) = self.fields(version) {
                version.id = entry.id;
                entry.history.push(HistoryEntry {
                    position,
                    entry: version,
                });
            }
        }
        Ok(entry)
    }

    /// Everything about an entry except its history.
    fn fields(&self, node: &Element) -> Result<VaultEntry, OpenError> {
        let mut id = None;
        let mut entry = VaultEntry {
            id: ItemId::from_bytes([0; 16]),
            title: FieldValue::default(),
            username: FieldValue::default(),
            password: SecretString::new(String::new()),
            url: FieldValue::default(),
            notes: FieldValue::default(),
            custom_fields: Vec::new(),
            tags: Vec::new(),
            attachments: Vec::new(),
            expires: None,
            icon: 0,
            custom_icon: None,
            modified: None,
            history: Vec::new(),
        };
        for child in node.elements() {
            match child.name.as_str() {
                "UUID" => id = Some(parse_uuid(child)?),
                "String" => {
                    let name = plain_text(child.child("Key").ok_or(ERR)?)?;
                    let value = match child.child("Value") {
                        Some(value) => field_value(value)?,
                        None => FieldValue::default(),
                    };
                    match name.as_str() {
                        "Title" => entry.title = value,
                        "UserName" => entry.username = value,
                        "Password" => entry.password = into_secret(value),
                        "URL" => entry.url = value,
                        "Notes" => entry.notes = value,
                        _ => entry.custom_fields.push(EntryField { name, value }),
                    }
                }
                "IconID" => {
                    if let Some(icon) = child.text().and_then(|t| t.trim().parse().ok()) {
                        entry.icon = icon;
                    }
                }
                "CustomIconUUID" => entry.custom_icon = parse_uuid(child).ok(),
                "Binary" => entry.attachments.push(self.attachment(child)?),
                "Tags" => entry.tags = split_tags(&plain_text(child)?),
                "Times" => {
                    entry.expires = expiry(child);
                    entry.modified = child_text(child, "LastModificationTime")
                        .and_then(|t| super::time::decode(&t));
                }
                _ => {}
            }
        }
        entry.id = id.ok_or(ERR)?;
        Ok(entry)
    }

    fn count_entry(&mut self) -> Result<(), OpenError> {
        self.entries += 1;
        if self.entries > self.limits.max_entries {
            return Err(OpenError::LimitExceeded(Limit::Entries));
        }
        Ok(())
    }

    fn attachment(&self, node: &Element) -> Result<Attachment, OpenError> {
        let name = plain_text(node.child("Key").ok_or(ERR)?)?;
        let reference = node
            .child("Value")
            .and_then(|value| value.attribute("Ref"))
            .and_then(|r| r.parse::<usize>().ok())
            .ok_or(ERR)?;
        let size = *self.attachment_sizes.get(reference).ok_or(ERR)?;
        Ok(Attachment {
            name,
            size,
            reference,
        })
    }
}

pub(crate) fn expiry(times: &Element) -> Option<i64> {
    let expires = child_text(times, "Expires")?;
    if !expires.trim().eq_ignore_ascii_case("true") {
        return None;
    }
    super::time::decode(&child_text(times, "ExpiryTime")?)
}

pub(crate) fn split_tags(text: &str) -> Vec<String> {
    text.split([';', ','])
        .map(str::trim)
        .filter(|tag| !tag.is_empty())
        .map(str::to_owned)
        .collect()
}

fn field_value(node: &Element) -> Result<FieldValue, OpenError> {
    match node.secret() {
        Some(secret) => Ok(FieldValue::Protected(SecretString::new(
            secret.expose().to_owned(),
        ))),
        None => Ok(FieldValue::Plain(plain_text(node)?)),
    }
}

fn plain_text(node: &Element) -> Result<String, OpenError> {
    node.text().ok_or(ERR)
}

fn into_secret(value: FieldValue) -> SecretString {
    match value {
        FieldValue::Protected(secret) => secret,
        FieldValue::Plain(plain) => SecretString::new(plain),
    }
}

fn child_text(node: &Element, name: &str) -> Option<String> {
    node.child(name).map(|n| n.text().unwrap_or_default())
}

pub(crate) fn parse_uuid(node: &Element) -> Result<ItemId, OpenError> {
    let bytes = STANDARD
        .decode(node.text().unwrap_or_default().trim())
        .map_err(|_| ERR)?;
    Ok(ItemId::from_bytes(bytes.try_into().map_err(|_| ERR)?))
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::kdbx::crypto::ProtectedStream;
    use crate::kdbx::dom;

    fn parse_str(xml: &str, limits: &OpenLimits) -> Result<(XmlMetadata, VaultGroup), OpenError> {
        let document = dom::parse(xml.as_bytes(), &mut ProtectedStream::none(), limits)?;
        project(&document, &[], limits)
    }

    const UUID: &str = "AAAAAAAAAAAAAAAAAAAAAQ==";

    #[test]
    fn rejects_doctype_entities() {
        let xml = format!(
            "<!DOCTYPE x [<!ENTITY a \"b\">]><KeePassFile><Root><Group><UUID>{UUID}</UUID></Group></Root></KeePassFile>"
        );
        assert_eq!(parse_str(&xml, &OpenLimits::default()).unwrap_err(), ERR);
    }

    #[test]
    fn enforces_group_depth() {
        let mut xml = String::from("<KeePassFile><Root>");
        xml.push_str(&format!("<Group><UUID>{UUID}</UUID>").repeat(5));
        xml.push_str(&"</Group>".repeat(5));
        xml.push_str("</Root></KeePassFile>");
        let limits = OpenLimits {
            max_group_depth: 3,
            ..OpenLimits::default()
        };
        assert_eq!(
            parse_str(&xml, &limits).unwrap_err(),
            OpenError::LimitExceeded(Limit::GroupDepth)
        );
        assert!(parse_str(&xml, &OpenLimits::default()).is_ok());
    }

    #[test]
    fn enforces_node_and_field_limits() {
        let xml = format!(
            "<KeePassFile><Root><Group><UUID>{UUID}</UUID><Name>{}</Name></Group></Root></KeePassFile>",
            "x".repeat(64)
        );
        let small_fields = OpenLimits {
            max_field_size: 16,
            ..OpenLimits::default()
        };
        assert_eq!(
            parse_str(&xml, &small_fields).unwrap_err(),
            OpenError::LimitExceeded(Limit::FieldSize)
        );
        let few_nodes = OpenLimits {
            max_xml_nodes: 4,
            ..OpenLimits::default()
        };
        assert_eq!(
            parse_str(&xml, &few_nodes).unwrap_err(),
            OpenError::LimitExceeded(Limit::XmlNodes)
        );
    }

    #[test]
    fn keeps_whitespace_and_accepts_any_element_order() {
        let xml = format!(
            "<KeePassFile><Root><Group><Entry><AutoType/><String><Key>Title</Key><Value>  padded  </Value></String><UUID>{UUID}</UUID><String><Key>Password</Key><Value/></String></Entry><UUID>{UUID}</UUID></Group></Root></KeePassFile>"
        );
        let (_, group) = parse_str(&xml, &OpenLimits::default()).unwrap();
        assert_eq!(group.entries[0].title.expose(), "  padded  ");
        assert!(group.entries[0].password.is_empty());
    }
}
