use keep_core::{VaultFormat, detect_format};
use serde::Deserialize;

const VECTORS: &str = include_str!("../../../shared/test-vectors/format-detection/vectors.json");

#[derive(Deserialize)]
struct Suite {
    vectors: Vec<Vector>,
}

#[derive(Deserialize)]
struct Vector {
    name: String,
    header_hex: String,
    expected: Expected,
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Expected {
    Kdbx { major: u16, minor: u16 },
    Keepass1,
    Unknown,
}

impl From<Expected> for VaultFormat {
    fn from(value: Expected) -> Self {
        match value {
            Expected::Kdbx { major, minor } => VaultFormat::Kdbx { major, minor },
            Expected::Keepass1 => VaultFormat::KeePass1,
            Expected::Unknown => VaultFormat::Unknown,
        }
    }
}

fn decode_hex(hex: &str) -> Vec<u8> {
    assert!(hex.len() % 2 == 0, "odd-length hex: {hex}");
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).expect("valid hex"))
        .collect()
}

#[test]
fn shared_format_detection_vectors() {
    let suite: Suite = serde_json::from_str(VECTORS).expect("vectors.json parses");
    assert!(!suite.vectors.is_empty());
    for vector in suite.vectors {
        let actual = detect_format(&decode_hex(&vector.header_hex));
        assert_eq!(actual, vector.expected.into(), "vector {}", vector.name);
    }
}
