use std::fmt::Write as _;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use roxmltree::{Document, Node, ParsingOptions};
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use crate::error::OpenError;

const XML_NODES_LIMIT: u32 = 10_000;
const HASH_PREFIX_LEN: usize = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyFileFormat {
    XmlV1,
    XmlV2,
    Binary32,
    Hex64,
    Hashed,
}

type Parsed = Result<(KeyFileFormat, Zeroizing<Vec<u8>>), OpenError>;

pub(crate) fn component(data: &[u8]) -> Parsed {
    if let Some(parsed) = xml_key(data) {
        return parsed;
    }
    if data.len() == 32 {
        return Ok((KeyFileFormat::Binary32, Zeroizing::new(data.to_vec())));
    }
    if data.len() == 64 {
        if let Some(key) = decode_hex(data) {
            return Ok((KeyFileFormat::Hex64, key));
        }
    }
    Ok((
        KeyFileFormat::Hashed,
        Zeroizing::new(Sha256::digest(data).to_vec()),
    ))
}

/// A new `KeePass` XML key file, version 2: 32 random bytes from the system CSPRNG as hex with the
/// SHA-256 check prefix, the layout `KeePass` 2.5x and `KeePassXC` write.
pub fn generate_key_file() -> Result<crate::SecretBytes, OpenError> {
    let key = crate::kdbx::writer::random::<32>().map_err(|_| OpenError::InvalidKeyFile)?;
    let digits = |bytes: &[u8]| {
        bytes
            .iter()
            .fold(Zeroizing::new(String::new()), |mut out, b| {
                let _ = write!(out, "{b:02X}");
                out
            })
    };
    let hex = digits(key.as_slice());
    let check = digits(&Sha256::digest(key.as_slice())[..HASH_PREFIX_LEN]);
    let mut body = Zeroizing::new(String::new());
    for line in hex.as_bytes().chunks(32) {
        body.push_str("\n\t\t\t");
        for (index, group) in line.chunks(8).enumerate() {
            if index > 0 {
                body.push(' ');
            }
            body.push_str(std::str::from_utf8(group).unwrap_or_default());
        }
    }
    let xml = Zeroizing::new(format!(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<KeyFile>\n\t<Meta>\n\t\t<Version>2.0</Version>\n\t</Meta>\n\t<Key>\n\t\t<Data Hash=\"{}\">{}\n\t\t</Data>\n\t</Key>\n</KeyFile>\n",
        check.as_str(),
        body.as_str()
    ));
    let bytes = xml.as_bytes().to_vec();
    // It must read back as the key it was made from.
    let (format, parsed) = component(&bytes)?;
    if format != KeyFileFormat::XmlV2 || parsed.as_slice() != key.as_slice() {
        return Err(OpenError::InvalidKeyFile);
    }
    Ok(crate::SecretBytes::new(bytes))
}

pub fn detect_key_file_format(data: &[u8]) -> Result<KeyFileFormat, OpenError> {
    component(data).map(|(format, _)| format)
}

fn xml_key(data: &[u8]) -> Option<Parsed> {
    let text = std::str::from_utf8(data).ok()?;
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    if !text.trim_start().starts_with('<') {
        return None;
    }
    let options = ParsingOptions {
        allow_dtd: false,
        nodes_limit: XML_NODES_LIMIT,
        ..ParsingOptions::default()
    };
    let document = Document::parse_with_options(text, options).ok()?;
    let root = document.root_element();
    if root.tag_name().name() != "KeyFile" || root.tag_name().namespace().is_some() {
        return None;
    }
    Some(parse_key_file(root))
}

fn parse_key_file(root: Node<'_, '_>) -> Parsed {
    const ERR: OpenError = OpenError::InvalidKeyFile;
    let version = child(root, "Meta")
        .and_then(|meta| child(meta, "Version"))
        .and_then(|node| node.text())
        .unwrap_or_default();
    let data = child(root, "Key").and_then(|key| child(key, "Data"));
    let mut encoded = Zeroizing::new(String::new());
    for text in data
        .iter()
        .flat_map(Node::children)
        .filter_map(|n| n.text())
    {
        encoded.extend(text.chars().filter(|c| !c.is_whitespace()));
    }
    if encoded.is_empty() {
        return Err(ERR);
    }
    let (format, key) = match major_version(version) {
        1 => (
            KeyFileFormat::XmlV1,
            Zeroizing::new(STANDARD.decode(encoded.as_bytes()).map_err(|_| ERR)?),
        ),
        2 => (
            KeyFileFormat::XmlV2,
            decode_hex(encoded.as_bytes()).ok_or(ERR)?,
        ),
        _ => return Err(ERR),
    };
    if key.is_empty() {
        return Err(ERR);
    }
    let hash = data.and_then(|d| d.attribute("Hash")).unwrap_or_default();
    if !hash.is_empty() {
        let expected = decode_hex(hash.as_bytes()).ok_or(ERR)?;
        if expected.as_slice() != &Sha256::digest(key.as_slice())[..HASH_PREFIX_LEN] {
            return Err(ERR);
        }
    }
    Ok((format, key))
}

fn child<'a, 'input>(node: Node<'a, 'input>, name: &str) -> Option<Node<'a, 'input>> {
    node.children()
        .find(|n| n.is_element() && n.tag_name().name() == name)
}

fn major_version(version: &str) -> u16 {
    version
        .trim()
        .split('.')
        .next()
        .and_then(|major| major.trim().parse().ok())
        .unwrap_or(0)
}

fn decode_hex(text: &[u8]) -> Option<Zeroizing<Vec<u8>>> {
    if text.len() % 2 != 0 {
        return None;
    }
    let digit = |d: u8| char::from(d).to_digit(16);
    let mut out = Zeroizing::new(Vec::with_capacity(text.len() / 2));
    for pair in text.chunks_exact(2) {
        let byte = digit(pair[0])? << 4 | digit(pair[1])?;
        out.push(u8::try_from(byte).ok()?);
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: [u8; 32] = [
        0x6f, 0x1c, 0x52, 0x90, 0x0a, 0xd7, 0x3e, 0x44, 0xb1, 0x08, 0x2f, 0x9e, 0x71, 0x35, 0xc0,
        0x18, 0x5d, 0xe2, 0x4a, 0x93, 0x07, 0xbb, 0x66, 0x21, 0xf4, 0x8c, 0x39, 0xd0, 0x12, 0x7a,
        0xe5, 0x4b,
    ];

    fn hex(bytes: &[u8]) -> String {
        bytes
            .iter()
            .fold(String::new(), |out, b| out + &format!("{b:02X}"))
    }

    fn v2(data: &str, hash: Option<&str>) -> String {
        let hash = hash.map(|h| format!(" Hash=\"{h}\"")).unwrap_or_default();
        format!(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<KeyFile>\n\t<Meta>\n\t\t<Version>2.0</Version>\n\t</Meta>\n\t<Key>\n\t\t<Data{hash}>{data}</Data>\n\t</Key>\n</KeyFile>"
        )
    }

    fn key_of(data: &[u8]) -> Result<(KeyFileFormat, Vec<u8>), OpenError> {
        component(data).map(|(format, key)| (format, key.to_vec()))
    }

    fn short_hash() -> String {
        hex(&Sha256::digest(KEY)[..4])
    }

    #[test]
    fn a_generated_key_file_is_xml_v2_and_unique() {
        let a = generate_key_file().unwrap();
        let b = generate_key_file().unwrap();
        assert_ne!(a.expose(), b.expose());
        let (format, key) = component(a.expose()).unwrap();
        assert_eq!(format, KeyFileFormat::XmlV2);
        assert_eq!(key.len(), 32);
        let text = std::str::from_utf8(a.expose()).unwrap();
        assert!(text.contains("<Version>2.0</Version>"));
    }

    #[test]
    fn xml_v2_with_pretty_printed_hex_and_hash() {
        let encoded = hex(&KEY);
        let pretty = format!(
            "\n\t\t\t{} {} {} {}\n\t\t\t{} {} {} {}\n\t\t",
            &encoded[0..8],
            &encoded[8..16],
            &encoded[16..24],
            &encoded[24..32],
            &encoded[32..40],
            &encoded[40..48],
            &encoded[48..56],
            &encoded[56..64]
        );
        let file = v2(&pretty, Some(&short_hash()));
        assert_eq!(
            key_of(file.as_bytes()).unwrap(),
            (KeyFileFormat::XmlV2, KEY.to_vec())
        );
        let lower = v2(&encoded.to_lowercase(), Some(&short_hash().to_lowercase()));
        assert_eq!(key_of(lower.as_bytes()).unwrap().1, KEY);
    }

    #[test]
    fn xml_v2_hash_is_optional_but_verified_when_present() {
        assert_eq!(
            key_of(v2(&hex(&KEY), None).as_bytes()).unwrap().1,
            KEY.to_vec()
        );
        for bad in ["00000000", "ABCD", "not-hex!", "1 2 3 4 "] {
            assert_eq!(
                key_of(v2(&hex(&KEY), Some(bad)).as_bytes()).unwrap_err(),
                OpenError::InvalidKeyFile,
                "{bad}"
            );
        }
    }

    #[test]
    fn xml_v1_is_base64() {
        let file = format!(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><KeyFile><Meta><Version>1.00</Version></Meta><Key><Data>{}</Data></Key></KeyFile>",
            STANDARD.encode(KEY)
        );
        assert_eq!(
            key_of(file.as_bytes()).unwrap(),
            (KeyFileFormat::XmlV1, KEY.to_vec())
        );
    }

    #[test]
    fn xml_key_length_is_not_forced_to_32_bytes() {
        let short = [7u8; 16];
        let file = v2(&hex(&short), Some(&hex(&Sha256::digest(short)[..4])));
        assert_eq!(key_of(file.as_bytes()).unwrap().1, short.to_vec());
    }

    #[test]
    fn broken_key_files_with_a_keyfile_root_are_errors() {
        let cases = [
            v2("", None),
            v2("ABC", None),
            v2("ZZ", None),
            v2(&hex(&KEY), Some(&short_hash())).replace("2.0", "3.0"),
            v2(&hex(&KEY), Some(&short_hash())).replace("<Version>2.0</Version>", ""),
            "<KeyFile><Meta><Version>1.0</Version></Meta><Key><Data>!!!</Data></Key></KeyFile>"
                .to_owned(),
        ];
        for case in cases {
            assert_eq!(
                key_of(case.as_bytes()).unwrap_err(),
                OpenError::InvalidKeyFile,
                "{case}"
            );
        }
    }

    #[test]
    fn other_xml_and_malformed_xml_fall_back_to_hashing() {
        let cases: [&[u8]; 4] = [
            b"<Other><Key><Data>00</Data></Key></Other>",
            b"<KeyFile><Meta>",
            b"<KeyFile xmlns=\"urn:x\"><Meta><Version>2.0</Version></Meta></KeyFile>",
            b"<!DOCTYPE KeyFile [<!ENTITY x \"y\">]><KeyFile/>",
        ];
        for case in cases {
            assert_eq!(
                key_of(case).unwrap(),
                (KeyFileFormat::Hashed, Sha256::digest(case).to_vec())
            );
        }
    }

    #[test]
    fn exactly_32_bytes_are_used_as_is() {
        assert_eq!(
            key_of(&KEY).unwrap(),
            (KeyFileFormat::Binary32, KEY.to_vec())
        );
    }

    #[test]
    fn exactly_64_hex_characters_are_decoded() {
        let encoded = hex(&KEY);
        assert_eq!(
            key_of(encoded.as_bytes()).unwrap(),
            (KeyFileFormat::Hex64, KEY.to_vec())
        );
        let not_hex = encoded.replace('A', "G");
        assert_eq!(
            key_of(not_hex.as_bytes()).unwrap(),
            (
                KeyFileFormat::Hashed,
                Sha256::digest(not_hex.as_bytes()).to_vec()
            )
        );
        let with_newline = format!("{encoded}\n");
        assert_eq!(
            key_of(with_newline.as_bytes()).unwrap().0,
            KeyFileFormat::Hashed
        );
    }

    #[test]
    fn anything_else_is_hashed_including_an_empty_file() {
        for case in [&b""[..], b"any file at all", &[0xffu8; 33], &[0u8; 4096]] {
            assert_eq!(
                key_of(case).unwrap(),
                (KeyFileFormat::Hashed, Sha256::digest(case).to_vec())
            );
        }
    }

    #[test]
    fn utf8_bom_is_accepted() {
        let file = format!("\u{feff}{}", v2(&hex(&KEY), Some(&short_hash())));
        assert_eq!(key_of(file.as_bytes()).unwrap().1, KEY.to_vec());
    }
}
