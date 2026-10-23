use std::collections::HashMap;
use std::path::PathBuf;

use keep_core::{
    KeyFileFormat, OpenError, SecretBytes, UnlockMaterial, Vault, VaultCredentials,
    detect_key_file_format, open_vault,
};
use serde::Deserialize;
use sha2::{Digest, Sha256};

#[derive(Deserialize)]
struct Suite {
    passwords: HashMap<String, String>,
    key_files: HashMap<String, KeyFileInfo>,
    contents: HashMap<String, Contents>,
    vectors: Vec<Vector>,
}

#[derive(Deserialize)]
struct KeyFileInfo {
    format: String,
    component: Option<String>,
    error: Option<String>,
}

#[derive(Deserialize)]
struct Contents {
    name: String,
    entries: Vec<ExpectedEntry>,
}

#[derive(Deserialize)]
struct ExpectedEntry {
    title: String,
    username: String,
    password: String,
    url: String,
}

#[derive(Deserialize)]
struct Vector {
    file: String,
    password: Option<String>,
    key_file: Option<String>,
    expect: Expect,
}

#[derive(Deserialize)]
#[serde(untagged)]
enum Expect {
    Error { error: String },
    Vault { contents: String },
}

fn dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../shared/test-vectors/keyfiles")
}

fn read(name: &str) -> Vec<u8> {
    std::fs::read(dir().join(name)).expect("vector file")
}

fn suite() -> Suite {
    serde_json::from_slice(&read("vectors.json")).expect("vectors.json parses")
}

fn credentials(suite: &Suite, vector: &Vector) -> Result<VaultCredentials, OpenError> {
    VaultCredentials::new(
        vector
            .password
            .as_ref()
            .map(|p| SecretBytes::from(suite.passwords[p].as_bytes().to_vec())),
        vector
            .key_file
            .as_deref()
            .map(|k| SecretBytes::from(read(k))),
    )
}

fn open(suite: &Suite, vector: &Vector) -> Result<Vault, OpenError> {
    open_vault(&read(&vector.file), &credentials(suite, vector)?)
}

fn code(error: OpenError) -> &'static str {
    match error {
        OpenError::InvalidCredentials => "invalid_credentials",
        OpenError::InvalidKeyFile => "invalid_key_file",
        OpenError::MissingCredentials => "missing_credentials",
        _ => "other",
    }
}

#[test]
fn checksums_match() {
    let sums = String::from_utf8(read("SHA256SUMS")).unwrap();
    let mut count = 0;
    for line in sums.lines() {
        let (hash, name) = line.split_once("  ").unwrap();
        let actual = Sha256::digest(read(name))
            .iter()
            .fold(String::new(), |out, b| out + &format!("{b:02x}"));
        assert_eq!(actual, hash, "{name}");
        count += 1;
    }
    assert_eq!(count, 14);
}

#[test]
fn every_vector_opens_or_fails_as_expected() {
    let suite = suite();
    assert!(suite.vectors.len() >= 17);
    for vector in &suite.vectors {
        let label = format!(
            "{} / {:?} / {:?}",
            vector.file, vector.password, vector.key_file
        );
        match (&vector.expect, open(&suite, vector)) {
            (Expect::Vault { contents }, Ok(vault)) => {
                let expected = &suite.contents[contents];
                assert_eq!(vault.metadata.name, expected.name, "{label}");
                let entry = &vault.root.entries[0];
                let want = &expected.entries[0];
                assert_eq!(entry.title.expose(), want.title, "{label}");
                assert_eq!(entry.username.expose(), want.username, "{label}");
                assert_eq!(entry.password.expose(), want.password, "{label}");
                assert_eq!(entry.url.expose(), want.url, "{label}");
            }
            (Expect::Error { error }, Err(actual)) => assert_eq!(code(actual), error, "{label}"),
            (Expect::Vault { .. }, Err(error)) => panic!("{label}: unexpected {error:?}"),
            (Expect::Error { error }, Ok(_)) => panic!("{label}: opened, expected {error}"),
        }
    }
}

#[test]
fn key_file_formats_are_detected() {
    let suite = suite();
    for (name, info) in &suite.key_files {
        let detected = detect_key_file_format(&read(name));
        if info.error.is_some() {
            assert_eq!(detected.unwrap_err(), OpenError::InvalidKeyFile, "{name}");
            continue;
        }
        let expected = match info.format.as_str() {
            "xml-v1" => KeyFileFormat::XmlV1,
            "xml-v2" => KeyFileFormat::XmlV2,
            "binary-32" => KeyFileFormat::Binary32,
            "hex-64" => KeyFileFormat::Hex64,
            _ => KeyFileFormat::Hashed,
        };
        assert_eq!(detected.unwrap(), expected, "{name}");
        assert!(info.component.as_ref().is_some_and(|c| c.len() == 64));
    }
}

#[test]
fn unlock_material_reopens_with_the_same_key_file_only() {
    let suite = suite();
    let vault = read("vault-password-plus-keyfile.kdbx");
    let password = SecretBytes::from(suite.passwords["standard"].as_bytes().to_vec());
    let manual = VaultCredentials::new(
        Some(password),
        Some(SecretBytes::from(read("keyfile-v2.keyx"))),
    )
    .unwrap();
    open_vault(&vault, &manual).unwrap();
    let encoded = manual.unlock_material().unwrap().encode();
    let material = UnlockMaterial::decode(encoded.expose()).unwrap();

    let remembered = VaultCredentials::from_unlock_material(
        &material,
        Some(SecretBytes::from(read("keyfile-v2.keyx"))),
    )
    .unwrap();
    assert_eq!(
        open_vault(&vault, &remembered).unwrap().metadata.name,
        "Key File Vault"
    );

    for key_file in [None, Some("keyfile-wrong.keyx")] {
        let credentials = VaultCredentials::from_unlock_material(
            &material,
            key_file.map(|k| SecretBytes::from(read(k))),
        )
        .unwrap();
        assert_eq!(
            open_vault(&vault, &credentials).unwrap_err(),
            OpenError::InvalidCredentials
        );
    }
}

#[test]
fn key_file_only_credentials_have_no_unlock_material() {
    let credentials =
        VaultCredentials::new(None, Some(SecretBytes::from(read("keyfile-v2.keyx")))).unwrap();
    assert!(credentials.unlock_material().is_none());
    assert!(open_vault(&read("vault-keyfile-only.kdbx"), &credentials).is_ok());
}
