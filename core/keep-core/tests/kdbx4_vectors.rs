use std::collections::{BTreeSet, HashMap};
use std::path::PathBuf;

use keep_core::{
    Cipher, Kdf, OpenError, SecretBytes, Vault, VaultCredentials, VaultEntry, open_vault,
};
use serde::Deserialize;

#[derive(Deserialize)]
struct Suite {
    passwords: HashMap<String, String>,
    contents: HashMap<String, Contents>,
    vectors: Vec<Vector>,
}

#[derive(Deserialize)]
struct Contents {
    name: String,
    description: String,
    groups: Vec<String>,
    entries: Vec<ExpectedEntry>,
}

#[derive(Deserialize)]
struct ExpectedEntry {
    group: String,
    title: String,
    username: String,
    password: String,
    url: String,
    notes: String,
    custom: Vec<ExpectedField>,
}

#[derive(Deserialize)]
struct ExpectedField {
    name: String,
    value: String,
    protected: bool,
}

#[derive(Deserialize)]
struct Vector {
    file: String,
    password: String,
    expect: Expect,
}

#[derive(Deserialize)]
#[serde(untagged)]
enum Expect {
    Error {
        error: String,
    },
    Vault {
        format: String,
        cipher: String,
        kdf: String,
        contents: String,
    },
}

fn vectors_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../shared/test-vectors/kdbx4")
}

fn load_suite() -> Suite {
    let json = std::fs::read_to_string(vectors_dir().join("vectors.json")).expect("vectors.json");
    serde_json::from_str(&json).expect("vectors.json parses")
}

fn open(suite: &Suite, vector: &Vector) -> Result<Vault, OpenError> {
    let data = std::fs::read(vectors_dir().join(&vector.file)).expect("vector file");
    let password = suite.passwords[&vector.password].as_bytes().to_vec();
    open_vault(
        &data,
        &VaultCredentials::from_password(SecretBytes::from(password)),
    )
}

fn error_name(error: OpenError) -> &'static str {
    match error {
        OpenError::InvalidFormat => "invalid_format",
        OpenError::UnsupportedVersion { .. } => "unsupported_version",
        OpenError::UnsupportedCipher => "unsupported_cipher",
        OpenError::UnsupportedKdf => "unsupported_kdf",
        OpenError::UnsupportedCompression => "unsupported_compression",
        OpenError::MissingCredentials => "missing_credentials",
        OpenError::InvalidCredentials => "invalid_credentials",
        OpenError::CorruptedVault => "corrupted_vault",
        OpenError::IntegrityCheckFailed => "integrity_check_failed",
        OpenError::DecompressionFailed => "decompression_failed",
        OpenError::XmlInvalid => "xml_invalid",
        OpenError::LimitExceeded(_) => "limit_exceeded",
        _ => "unknown",
    }
}

fn entries_by_path(vault: &Vault) -> Vec<(String, &VaultEntry)> {
    vault
        .root
        .walk()
        .flat_map(|(path, group)| {
            let path = path.join("/");
            group.entries.iter().map(move |entry| (path.clone(), entry))
        })
        .collect()
}

fn check_contents(file: &str, vault: &Vault, expected: &Contents) {
    assert_eq!(vault.metadata.name, expected.name, "{file}: name");
    assert_eq!(
        vault.metadata.description, expected.description,
        "{file}: description"
    );

    let groups: BTreeSet<String> = vault
        .root
        .walk()
        .map(|(path, _)| path.join("/"))
        .filter(|path| !path.is_empty())
        .collect();
    let expected_groups: BTreeSet<String> = expected.groups.iter().cloned().collect();
    assert_eq!(groups, expected_groups, "{file}: groups");

    let entries = entries_by_path(vault);
    assert_eq!(entries.len(), expected.entries.len(), "{file}: entry count");
    for want in &expected.entries {
        let (_, entry) = entries
            .iter()
            .find(|(path, entry)| *path == want.group && entry.title.expose() == want.title)
            .unwrap_or_else(|| panic!("{file}: missing entry {:?}/{:?}", want.group, want.title));
        let label = format!("{file}: {}", want.title);
        assert_eq!(entry.username.expose(), want.username, "{label} username");
        assert_eq!(entry.password.expose(), want.password, "{label} password");
        assert_eq!(entry.url.expose(), want.url, "{label} url");
        assert_eq!(entry.notes.expose(), want.notes, "{label} notes");
        assert_eq!(
            entry.custom_fields.len(),
            want.custom.len(),
            "{label} custom count"
        );
        for field in &want.custom {
            let actual = entry
                .custom_fields
                .iter()
                .find(|f| f.name == field.name)
                .unwrap_or_else(|| panic!("{label}: missing custom {}", field.name));
            assert_eq!(
                actual.value.expose(),
                field.value,
                "{label} custom {}",
                field.name
            );
            assert_eq!(
                actual.value.is_protected(),
                field.protected,
                "{label} protected {}",
                field.name
            );
        }
    }
}

#[test]
fn shared_kdbx4_vectors() {
    let suite = load_suite();
    for vector in &suite.vectors {
        let result = open(&suite, vector);
        match &vector.expect {
            Expect::Error { error } => {
                let actual = result.map(|_| ()).expect_err(&vector.file);
                assert_eq!(error_name(actual), error, "{}", vector.file);
            }
            Expect::Vault {
                format,
                cipher,
                kdf,
                contents,
            } => {
                let vault = result.unwrap_or_else(|e| panic!("{}: {e}", vector.file));
                let meta = &vault.metadata;
                assert_eq!(
                    format!("{}.{}", meta.format.major, meta.format.minor),
                    *format
                );
                let actual_cipher = match meta.cipher {
                    Cipher::Aes256 => "aes256",
                    Cipher::ChaCha20 => "chacha20",
                };
                let actual_kdf = match meta.kdf {
                    Kdf::AesKdf => "aes-kdf",
                    Kdf::Argon2d => "argon2d",
                    Kdf::Argon2id => "argon2id",
                };
                assert_eq!(
                    (actual_cipher, actual_kdf),
                    (cipher.as_str(), kdf.as_str()),
                    "{}",
                    vector.file
                );
                check_contents(&vector.file, &vault, &suite.contents[contents]);
            }
        }
    }
}

#[test]
fn debug_output_contains_no_vault_content() {
    let suite = load_suite();
    let vault = open(&suite, &suite.vectors[0]).expect("opens");
    let debug = format!("{vault:?}");
    for needle in [
        "hunter2",
        "alice",
        "Example Mail",
        "RC-0000",
        "Synthetic Test Vault",
    ] {
        assert!(!debug.contains(needle), "Debug leaks {needle}");
    }
}

#[test]
fn every_vector_file_is_covered() {
    let suite = load_suite();
    let covered: BTreeSet<&str> = suite.vectors.iter().map(|v| v.file.as_str()).collect();
    for entry in std::fs::read_dir(vectors_dir()).expect("dir") {
        let path = entry.expect("entry").path();
        if path
            .extension()
            .is_some_and(|ext| ext.eq_ignore_ascii_case("kdbx"))
        {
            let name = path.file_name().and_then(|n| n.to_str()).expect("utf8");
            assert!(covered.contains(name), "{name} has no vector");
        }
    }
}
