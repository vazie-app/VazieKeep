//! Entry history through the vault session: keeping it is free, reading a version and going back
//! to one need Keep+, and a restore loses nothing.

use std::path::PathBuf;

use keep_core::{
    Access, AccessState, ChangedField, EditError, EntryDraft, HistoryError, ItemId, SecretBytes,
    SecretField, SecretString, VaultCredentials, VaultSession, open_vault,
};

const STANDARD: &str = "correct horse battery staple";

fn access(plus: bool) -> Access {
    Access {
        state: if plus {
            AccessState::PlusActive
        } else {
            AccessState::Free
        },
        plus_features: plus,
        needs_online_verification: false,
        lease_valid_until: None,
        observed_device_time: 0,
    }
}

fn open(bytes: &[u8]) -> VaultSession {
    let credentials =
        VaultCredentials::from_password(SecretBytes::from(STANDARD.as_bytes().to_vec()));
    VaultSession::new(open_vault(bytes, &credentials).expect("opens")).expect("session")
}

fn rich() -> VaultSession {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../shared/test-vectors/editable/rich-aes-argon2d-gzip.kdbx");
    open(&std::fs::read(path).unwrap())
}

fn reopen(session: &mut VaultSession) -> VaultSession {
    let snapshot = session.prepare_save().expect("saves");
    assert!(session.mark_saved(snapshot.generation));
    open(&snapshot.bytes)
}

fn bank(session: &VaultSession) -> ItemId {
    session
        .entries()
        .find(|e| e.title.as_plain() == Some("Bank"))
        .expect("Bank")
        .id
}

fn password(session: &VaultSession, id: ItemId) -> String {
    session
        .reveal(id, SecretField::Password)
        .unwrap()
        .expose()
        .to_owned()
}

fn draft(session: &VaultSession, id: ItemId, password: &str) -> EntryDraft {
    let detail = session.entry(id).unwrap();
    EntryDraft {
        title: detail.summary.title.as_plain().unwrap().to_owned(),
        username: detail.summary.username.as_plain().unwrap().to_owned(),
        password: SecretString::new(password.to_owned()),
        url: detail.summary.url.as_plain().unwrap().to_owned(),
        notes: session
            .reveal(id, SecretField::Notes)
            .unwrap()
            .expose()
            .to_owned(),
        custom_fields: Vec::new(),
        tags: detail.summary.tags.clone(),
        favorite: detail.summary.favorite,
        expires: detail.summary.expires,
    }
}

#[test]
fn the_count_is_free_and_the_versions_need_keep_plus() {
    let session = rich();
    let id = bank(&session);
    assert_eq!(session.entry(id).unwrap().history_count, 2);

    let free = access(false);
    assert_eq!(
        session.history(id, &free).unwrap_err(),
        HistoryError::NotEntitled
    );
    assert_eq!(
        session.history_entry(id, 0, &free).unwrap_err(),
        HistoryError::NotEntitled
    );
    assert_eq!(
        session
            .reveal_history(id, 0, SecretField::Password, &free)
            .unwrap_err(),
        HistoryError::NotEntitled
    );
}

#[test]
fn versions_come_newest_first_and_say_what_differs() {
    let mut session = rich();
    let id = bank(&session);
    let before = password(&session, id);
    session
        .update_entry(id, &draft(&session, id, "a-new-password"))
        .unwrap();

    let plus = access(true);
    let versions = session.history(id, &plus).unwrap();
    assert_eq!(versions.len(), 3);
    assert_eq!(session.entry(id).unwrap().history_count, 3);
    let newest = &versions[0];
    assert_eq!(
        newest.index, 2,
        "the newest version is the last in the file"
    );
    assert_eq!(newest.differs, vec![ChangedField::Password]);
    assert_eq!(newest.title.as_plain(), Some("Bank"));
    assert!(versions.windows(2).all(|w| w[0].index > w[1].index));
    assert!(versions.iter().all(|v| v.modified.is_some()));

    let old = session
        .reveal_history(id, newest.index, SecretField::Password, &plus)
        .unwrap();
    assert_eq!(old.expose(), before);
    let detail = session.history_entry(id, newest.index, &plus).unwrap();
    assert_eq!(detail.summary.id, id);
    assert_eq!(detail.history_count, 0);
    assert_eq!(
        session.history_entry(id, 99, &plus).unwrap_err(),
        HistoryError::VersionNotFound
    );
}

#[test]
fn going_back_to_a_version_keeps_the_current_one_in_the_history() {
    let mut session = rich();
    let id = bank(&session);
    let plus = access(true);
    let current = password(&session, id);
    let oldest = session.history(id, &plus).unwrap().pop().unwrap();
    let wanted = session
        .reveal_history(id, oldest.index, SecretField::Password, &plus)
        .unwrap()
        .expose()
        .to_owned();
    assert_ne!(wanted, current);

    assert_eq!(
        session
            .restore_history(id, oldest.index, &access(false))
            .unwrap_err(),
        EditError::NotEntitled
    );
    assert!(!session.is_dirty());
    assert_eq!(
        session.restore_history(id, 99, &plus).unwrap_err(),
        EditError::VersionNotFound
    );

    session.restore_history(id, oldest.index, &plus).unwrap();
    assert!(session.is_dirty());
    assert_eq!(password(&session, id), wanted);
    assert_eq!(bank(&session), id, "the entry keeps its identity");

    let reopened = reopen(&mut session);
    assert_eq!(password(&reopened, id), wanted);
    let versions = reopened.history(id, &plus).unwrap();
    assert_eq!(versions.len(), 3);
    let previous = reopened
        .reveal_history(id, versions[0].index, SecretField::Password, &plus)
        .unwrap();
    assert_eq!(
        previous.expose(),
        current,
        "what was current is the newest version"
    );
}

#[test]
fn an_entry_with_no_history_has_none() {
    let mut session = rich();
    let root = session.info().root_group;
    let id = session
        .create_entry(
            root,
            &EntryDraft {
                title: "Fresh".to_owned(),
                username: String::new(),
                password: SecretString::new("one".to_owned()),
                url: String::new(),
                notes: String::new(),
                custom_fields: Vec::new(),
                tags: Vec::new(),
                favorite: false,
                expires: None,
            },
        )
        .unwrap();
    assert_eq!(session.entry(id).unwrap().history_count, 0);
    assert!(session.history(id, &access(true)).unwrap().is_empty());
}
