//! Attachments through the vault session: reading is free, adding needs Keep+, identical content is
//! stored once, removal keeps history, and a permanent delete drops binaries nothing uses.

use std::path::PathBuf;

use keep_core::{
    Access, AccessState, EditError, EntryDraft, ItemId, MAX_ATTACHMENT_SIZE, SecretBytes,
    SecretString, VaultCredentials, VaultSession, open_vault,
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

fn find(session: &VaultSession, title: &str) -> ItemId {
    session
        .entries()
        .find(|e| e.title.as_plain() == Some(title))
        .unwrap_or_else(|| panic!("{title}"))
        .id
}

fn names(session: &VaultSession, id: ItemId) -> Vec<(String, usize)> {
    session
        .entry(id)
        .unwrap()
        .attachments
        .into_iter()
        .map(|a| (a.name, a.size))
        .collect()
}

#[test]
fn an_existing_attachment_reads_back_whole() {
    let session = rich();
    let id = find(&session, "With Attachment");
    let [(name, size)] = names(&session, id).try_into().unwrap();
    assert_eq!(name, "notes.bin");
    let bytes = session.attachment(id, &name).unwrap();
    assert_eq!(bytes.expose().len(), size);
    assert!(bytes.expose().starts_with(b"synthetic attachment "));
    assert!(session.attachment(id, "missing.bin").is_err());
}

#[test]
fn adding_needs_keep_plus_and_survives_a_save() {
    let mut session = rich();
    let mail = find(&session, "Example Mail");
    assert_eq!(
        session.add_attachment(mail, "scan.pdf", b"%PDF-1.7", &access(false)),
        Err(EditError::NotEntitled)
    );
    assert!(!session.is_dirty());

    assert_eq!(
        session
            .add_attachment(mail, "scan.pdf", b"%PDF-1.7", &access(true))
            .unwrap(),
        "scan.pdf"
    );
    assert_eq!(
        session
            .add_attachment(mail, "scan.pdf", b"other", &access(true))
            .unwrap(),
        "scan (2).pdf"
    );
    let reopened = reopen(&mut session);
    assert_eq!(
        names(&reopened, mail),
        [("scan.pdf".to_owned(), 8), ("scan (2).pdf".to_owned(), 5)]
    );
    assert_eq!(
        reopened.attachment(mail, "scan (2).pdf").unwrap().expose(),
        b"other"
    );
}

#[test]
fn identical_content_is_stored_once() {
    let mut session = rich();
    let with = find(&session, "With Attachment");
    let existing = session.attachment(with, "notes.bin").unwrap();
    let mail = find(&session, "Example Mail");
    let before = session.prepare_save().unwrap().bytes.len();
    session
        .add_attachment(mail, "copy.bin", existing.expose(), &access(true))
        .unwrap();
    let after = session.prepare_save().unwrap().bytes.len();
    assert!(
        after < before + existing.expose().len() / 2,
        "{before} -> {after}"
    );
    let reopened = reopen(&mut session);
    assert_eq!(
        reopened.attachment(mail, "copy.bin").unwrap().expose(),
        existing.expose()
    );
}

#[test]
fn removing_is_free_and_a_permanent_delete_drops_unused_binaries() {
    let mut session = rich();
    let web = session
        .groups()
        .iter()
        .find(|g| g.name == "Web")
        .unwrap()
        .id;
    let draft = EntryDraft {
        title: "Temporary".to_owned(),
        username: String::new(),
        password: SecretString::new(String::new()),
        url: String::new(),
        notes: String::new(),
        custom_fields: Vec::new(),
        tags: Vec::new(),
        favorite: false,
        expires: None,
    };
    let id = session.create_entry(web, &draft).unwrap();
    let big = vec![7u8; 200_000];
    session
        .add_attachment(id, "big.bin", &big, &access(true))
        .unwrap();
    session.remove_attachment(id, "big.bin").unwrap();
    assert!(names(&session, id).is_empty());
    assert_eq!(
        session.remove_attachment(id, "big.bin"),
        Err(EditError::AttachmentNotFound)
    );
    let kept = session.prepare_save().unwrap().bytes.len();

    session.delete_entry(id, true).unwrap();
    let compacted = session.prepare_save().unwrap().bytes.len();
    assert!(compacted + 100 < kept, "{kept} -> {compacted}");
    let mut reopened = reopen(&mut session);
    let with = find(&reopened, "With Attachment");
    assert!(
        reopened
            .attachment(with, "notes.bin")
            .unwrap()
            .expose()
            .starts_with(b"synthetic")
    );
    reopened.delete_entry(with, true).unwrap();
    reopen(&mut reopened);
}

#[test]
fn oversized_and_unnamed_attachments_are_refused() {
    let mut session = rich();
    let mail = find(&session, "Example Mail");
    let huge = vec![0u8; MAX_ATTACHMENT_SIZE + 1];
    assert_eq!(
        session.add_attachment(mail, "huge.bin", &huge, &access(true)),
        Err(EditError::AttachmentTooLarge)
    );
    assert_eq!(
        session.add_attachment(mail, "  ", b"x", &access(true)),
        Err(EditError::InvalidText)
    );
    assert!(!session.is_dirty());
}
