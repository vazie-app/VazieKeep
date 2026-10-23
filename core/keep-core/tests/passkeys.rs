//! A passkey lives in an entry the way `KeePassXC` keeps it, survives a save and an edit of the
//! entry, and signs only for its own site.

use keep_core::passkey;
use keep_core::{PasskeyError, PasskeyRegistration, VaultSession};
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use p256::pkcs8::DecodePublicKey;
use sha2::{Digest, Sha256};

fn open(bytes: &[u8]) -> VaultSession {
    let credentials = keep_core::VaultCredentials::from_password(keep_core::SecretBytes::from(
        b"correct horse battery staple".to_vec(),
    ));
    VaultSession::new(keep_core::open_vault(bytes, &credentials).unwrap()).unwrap()
}

fn rich() -> VaultSession {
    let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../shared/test-vectors/editable/rich-aes-argon2d-gzip.kdbx");
    open(&std::fs::read(path).unwrap())
}

fn registration<'a>(rp_id: &'a str, user: &'a str, handle: &'a [u8]) -> PasskeyRegistration<'a> {
    PasskeyRegistration {
        rp_id,
        rp_name: "Example Shop",
        user_name: user,
        user_handle: handle,
    }
}

#[test]
fn a_passkey_is_kept_in_an_entry_and_signs_after_a_save() {
    let mut session = rich();
    let group = session.entries().next().unwrap().group;
    let made = session
        .create_passkey(group, &registration("shop.example.com", "dana", b"user-42"))
        .unwrap();

    let detail = session.entry(made.entry).unwrap();
    assert_eq!(detail.summary.title.as_plain(), Some("Example Shop"));
    assert_eq!(
        detail.summary.url.as_plain(),
        Some("https://shop.example.com")
    );
    assert!(detail.summary.tags.iter().any(|t| t == passkey::TAG));
    assert!(
        detail.custom_fields.is_empty(),
        "the passkey is not shown as custom fields"
    );
    let info = detail.summary.passkey.expect("the passkey");
    assert_eq!(info.rp_id, "shop.example.com");
    assert_eq!(info.user_name, "dana");
    assert_eq!(info.credential_id, passkey::encode_id(&made.credential_id));
    assert_eq!(info.user_handle, passkey::encode_id(b"user-42"));

    let snapshot = session.prepare_save().unwrap();
    let reopened = open(&snapshot.bytes);
    let listed = reopened.passkeys(Some("shop.example.com"));
    assert_eq!(listed.len(), 1);
    assert!(
        reopened.passkeys(Some("example.com")).is_empty(),
        "a parent domain is another site"
    );
    assert_eq!(reopened.passkeys(None).len(), 1);

    let hash = Sha256::digest(b"client data");
    let answer = reopened
        .sign_passkey(made.entry, "shop.example.com", &hash)
        .unwrap();
    assert_eq!(answer.credential_id, made.credential_id);
    assert_eq!(answer.user_handle, b"user-42");
    let key = VerifyingKey::from_public_key_der(&made.public_key).unwrap();
    let mut signed = answer.authenticator_data.clone();
    signed.extend_from_slice(&hash);
    key.verify(&signed, &Signature::from_der(&answer.signature).unwrap())
        .unwrap();

    assert_eq!(
        reopened.sign_passkey(made.entry, "evil.example.com", &hash),
        Err(PasskeyError::InvalidRequest),
        "a key signs only for the site it was made for"
    );
}

#[test]
fn the_fields_are_the_ones_keepassxc_reads() {
    let mut session = rich();
    let group = session.entries().next().unwrap().group;
    let made = session
        .create_passkey(group, &registration("example.org", "erin", &[1, 2, 3]))
        .unwrap();
    let reveal = |name: &str| {
        session
            .reveal(made.entry, keep_core::SecretField::Custom(name))
            .unwrap()
            .expose()
            .to_owned()
    };
    assert_eq!(reveal("KPEX_PASSKEY_RELYING_PARTY"), "example.org");
    assert_eq!(reveal("KPEX_PASSKEY_USERNAME"), "erin");
    assert_eq!(reveal("KPEX_PASSKEY_USER_HANDLE"), "AQID");
    assert_eq!(
        reveal("KPEX_PASSKEY_CREDENTIAL_ID"),
        passkey::encode_id(&made.credential_id)
    );
    assert!(reveal("KPEX_PASSKEY_PRIVATE_KEY_PEM").starts_with("-----BEGIN PRIVATE KEY-----"));
    assert_eq!(reveal("KPEX_PASSKEY_FLAG_BE"), "1");
    assert_eq!(reveal("KPEX_PASSKEY_FLAG_BS"), "1");
}

#[test]
fn editing_the_entry_keeps_its_passkey_and_removing_it_is_its_own_step() {
    let mut session = rich();
    let group = session.entries().next().unwrap().group;
    let made = session
        .create_passkey(group, &registration("example.org", "erin", b"h"))
        .unwrap();
    let draft = keep_core::EntryDraft {
        title: "Renamed".to_owned(),
        username: "erin".to_owned(),
        password: keep_core::SecretString::new("added later".to_owned()),
        url: "https://example.org".to_owned(),
        notes: "note".to_owned(),
        custom_fields: Vec::new(),
        tags: vec![passkey::TAG.to_owned()],
        favorite: false,
        expires: None,
    };
    session.update_entry(made.entry, &draft).unwrap();
    assert!(session.entry(made.entry).unwrap().summary.passkey.is_some());
    let hash = [7u8; 32];
    session
        .sign_passkey(made.entry, "example.org", &hash)
        .unwrap();
    let before = session.entry(made.entry).unwrap().history_count;
    session.update_entry(made.entry, &draft).unwrap();
    assert_eq!(
        session.entry(made.entry).unwrap().history_count,
        before,
        "nothing changed, no version"
    );

    session.remove_passkey(made.entry).unwrap();
    let detail = session.entry(made.entry).unwrap();
    assert_eq!(detail.summary.passkey, None);
    assert!(!detail.summary.tags.iter().any(|t| t == passkey::TAG));
    assert_eq!(
        session.sign_passkey(made.entry, "example.org", &hash),
        Err(PasskeyError::NoPasskey)
    );
    assert!(session.passkeys(None).is_empty());
}

#[test]
fn a_deleted_entry_does_not_sign_in_and_a_bad_request_makes_nothing() {
    let mut session = rich();
    let group = session.entries().next().unwrap().group;
    let count = session.entries().count();
    assert!(
        session
            .create_passkey(group, &registration("", "x", b"h"))
            .is_err()
    );
    assert!(
        session
            .create_passkey(group, &registration("example.org", "x", b""))
            .is_err()
    );
    assert_eq!(session.entries().count(), count);
    assert!(!session.is_dirty());

    let made = session
        .create_passkey(group, &registration("example.org", "erin", b"h"))
        .unwrap();
    session.delete_entry(made.entry, false).unwrap();
    assert!(session.passkeys(None).is_empty());
    assert_eq!(
        session.sign_passkey(made.entry, "example.org", &[0u8; 32]),
        Err(PasskeyError::NoPasskey)
    );
}
