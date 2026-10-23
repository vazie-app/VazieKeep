//! One-time codes through the vault session: an entry's `otp` field produces codes, survives a save,
//! and never shows its secret to anything but a reveal of that field.

use std::path::PathBuf;

use keep_core::{
    Access, AccessState, CustomFieldDraft, EntryDraft, ItemId, SecretBytes, SecretField,
    SecretString, TotpError, TotpLookupError, VaultCredentials, VaultSession, open_vault,
};

fn plus() -> Access {
    Access {
        state: AccessState::PlusActive,
        plus_features: true,
        needs_online_verification: false,
        lease_valid_until: None,
        observed_device_time: 0,
    }
}

fn free() -> Access {
    Access {
        state: AccessState::Free,
        plus_features: false,
        needs_online_verification: false,
        lease_valid_until: None,
        observed_device_time: 0,
    }
}

const STANDARD: &str = "correct horse battery staple";
const SECRET: &str =
    "otpauth://totp/Example:anna?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example&digits=8";

fn open(bytes: &[u8]) -> VaultSession {
    let credentials =
        VaultCredentials::new(Some(SecretBytes::from(STANDARD.as_bytes().to_vec())), None).unwrap();
    VaultSession::new(open_vault(bytes, &credentials).expect("opens")).expect("session")
}

fn fixture() -> VaultSession {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../shared/test-vectors/editable/rich-aes-argon2d-gzip.kdbx");
    open(&std::fs::read(path).unwrap())
}

fn entry_with(session: &mut VaultSession, fields: Vec<(&str, &str)>) -> ItemId {
    let root = session.info().root_group;
    let draft = EntryDraft {
        title: "Mail".to_owned(),
        username: "anna".to_owned(),
        password: SecretString::new("pw".to_owned()),
        url: String::new(),
        notes: String::new(),
        custom_fields: fields
            .into_iter()
            .map(|(name, value)| CustomFieldDraft {
                name: name.to_owned(),
                value: SecretString::new(value.to_owned()),
                protected: true,
            })
            .collect(),
        tags: Vec::new(),
        favorite: false,
        expires: None,
    };
    session.create_entry(root, &draft).unwrap()
}

#[test]
fn an_entry_with_an_otp_field_yields_codes() {
    let mut session = fixture();
    let id = entry_with(&mut session, vec![("otp", SECRET)]);

    let summary = session.entries().find(|e| e.id == id).unwrap();
    assert!(summary.has_totp);
    let code = session.totp(id, 59, &plus()).unwrap();
    assert_eq!(code.code.expose(), "94287082");
    assert_eq!(code.period, 30);
    assert_eq!(code.seconds_remaining, 1);
}

#[test]
fn an_entry_without_one_has_no_code() {
    let mut session = fixture();
    let id = entry_with(&mut session, vec![("Recovery", "abc")]);

    assert!(!session.entries().find(|e| e.id == id).unwrap().has_totp);
    assert_eq!(
        session.totp(id, 59, &plus()).unwrap_err(),
        TotpLookupError::NoCode
    );
}

#[test]
fn a_broken_setup_is_reported_not_hidden() {
    let mut session = fixture();
    let id = entry_with(&mut session, vec![("otp", "otpauth://totp/x?secret=!!")]);

    assert!(!session.entries().find(|e| e.id == id).unwrap().has_totp);
    assert_eq!(
        session.totp(id, 59, &plus()).unwrap_err(),
        TotpLookupError::Invalid(TotpError::InvalidSecret)
    );
}

#[test]
fn an_unknown_entry_is_a_session_error() {
    let session = fixture();
    assert!(matches!(
        session.totp(ItemId::from_bytes([9; 16]), 59, &plus()),
        Err(TotpLookupError::Session(_))
    ));
}

#[test]
fn the_code_setup_survives_a_save_and_the_summary_never_carries_the_secret() {
    let mut session = fixture();
    let id = entry_with(&mut session, vec![("otp", SECRET)]);
    let snapshot = session.prepare_save().unwrap();
    assert!(session.mark_saved(snapshot.generation));

    let reopened = open(&snapshot.bytes);
    assert_eq!(
        reopened.totp(id, 59, &plus()).unwrap().code.expose(),
        "94287082"
    );
    let detail = reopened.entry(id).unwrap();
    let shown = format!(
        "{detail:?}{:?}",
        reopened.entries().find(|e| e.id == id).unwrap()
    );
    assert!(!shown.contains("GEZDGNBV"));
    assert_eq!(
        reopened
            .reveal(id, SecretField::Custom("otp"))
            .unwrap()
            .expose(),
        SECRET
    );
}

#[test]
fn without_keep_plus_no_code_is_ever_made_and_nothing_is_learned() {
    let mut session = fixture();
    let working = entry_with(&mut session, vec![("otp", SECRET)]);
    let broken = entry_with(&mut session, vec![("otp", "otpauth://totp/x?secret=!!")]);
    let none = entry_with(&mut session, vec![("Recovery", "abc")]);

    for id in [working, broken, none] {
        assert_eq!(
            session.totp(id, 59, &free()).unwrap_err(),
            TotpLookupError::NotEntitled
        );
    }
    let expired = Access {
        state: AccessState::PlusExpired,
        plus_features: false,
        ..free()
    };
    assert_eq!(
        session.totp(working, 59, &expired).unwrap_err(),
        TotpLookupError::NotEntitled
    );
}
