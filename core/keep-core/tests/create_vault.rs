//! A new vault goes through the same reader, session and writer as any other.

use keep_core::{
    Argon2Settings, Cipher, CreateVaultConfig, EntryDraft, SecretBytes, SecretString,
    SecurityPreset, VaultCredentials, VaultSession, create_vault, generate_key_file, open_vault,
};

fn fast(name: &str) -> CreateVaultConfig {
    CreateVaultConfig {
        name: name.to_owned(),
        kdf: Argon2Settings {
            memory_kib: 8 * 1024,
            iterations: 1,
            parallelism: 1,
        },
        cipher: Cipher::Aes256,
    }
}

fn password(text: &str) -> VaultCredentials {
    VaultCredentials::from_password(SecretBytes::new(text.as_bytes().to_vec()))
}

#[test]
fn create_edit_save_and_reopen() {
    let credentials = password("correct horse");
    let created = create_vault(&fast("Personal"), &credentials).unwrap();
    let vault = open_vault(&created.bytes, &credentials).unwrap();
    let mut session = VaultSession::new(vault).unwrap();
    assert!(session.is_editable());
    let draft = EntryDraft {
        title: "Mail".to_owned(),
        username: "alice".to_owned(),
        password: SecretString::new("hunter2".to_owned()),
        url: "https://mail.example".to_owned(),
        notes: String::new(),
        tags: Vec::new(),
        favorite: false,
        expires: None,
        custom_fields: Vec::new(),
    };
    let root = session.info().root_group;
    session.create_entry(root, &draft).unwrap();
    let snapshot = session.prepare_save().unwrap();

    let reopened = open_vault(&snapshot.bytes, &credentials).unwrap();
    let session = VaultSession::new(reopened).unwrap();
    assert_eq!(session.info().entry_count, 1);
    assert_eq!(session.info().name, "Personal");
}

#[test]
fn a_new_vault_can_delete_to_its_recycle_bin() {
    let credentials = password("pw");
    let created = create_vault(&fast("Bin"), &credentials).unwrap();
    let mut session = VaultSession::new(open_vault(&created.bytes, &credentials).unwrap()).unwrap();
    let draft = EntryDraft {
        title: "Gone".to_owned(),
        username: String::new(),
        password: SecretString::new("x".to_owned()),
        url: String::new(),
        notes: String::new(),
        tags: Vec::new(),
        favorite: false,
        expires: None,
        custom_fields: Vec::new(),
    };
    let root = session.info().root_group;
    let id = session.create_entry(root, &draft).unwrap();
    session.delete_entry(id, false).unwrap();
    assert!(session.info().recycle_bin.is_some());
}

#[test]
fn password_plus_generated_key_file() {
    let key_file = generate_key_file().unwrap();
    let credentials = VaultCredentials::new(
        Some(SecretBytes::new(b"pw".to_vec())),
        Some(SecretBytes::new(key_file.expose().to_vec())),
    )
    .unwrap();
    let created = create_vault(&fast("Keyed"), &credentials).unwrap();
    assert!(open_vault(&created.bytes, &credentials).is_ok());
    assert!(open_vault(&created.bytes, &password("pw")).is_err());
    let only_key =
        VaultCredentials::new(None, Some(SecretBytes::new(key_file.expose().to_vec()))).unwrap();
    assert!(open_vault(&created.bytes, &only_key).is_err());
}

#[test]
fn key_file_alone_is_enough_when_it_is_all_that_was_used() {
    let key_file = generate_key_file().unwrap();
    let credentials =
        VaultCredentials::new(None, Some(SecretBytes::new(key_file.expose().to_vec()))).unwrap();
    let created = create_vault(&fast("KeyOnly"), &credentials).unwrap();
    assert!(open_vault(&created.bytes, &credentials).is_ok());
}

#[test]
fn unicode_names_and_presets() {
    let credentials = password("пароль");
    let mut config = CreateVaultConfig::new("Семейный сейф 🔐", SecurityPreset::Balanced);
    config.kdf.memory_kib = 8 * 1024;
    config.kdf.iterations = 1;
    let created = create_vault(&config, &credentials).unwrap();
    let session = VaultSession::new(open_vault(&created.bytes, &credentials).unwrap()).unwrap();
    assert_eq!(session.info().name, "Семейный сейф 🔐");
}

#[test]
fn creation_without_credentials_is_refused() {
    let nothing = VaultCredentials::new(None, None);
    assert!(nothing.is_err());
}
