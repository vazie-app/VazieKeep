//! Saving: read → write → read must give the same vault, for every format variant the reader
//! accepts, and edits must survive a reopen.

use std::fmt::Write as _;
use std::path::PathBuf;

use keep_core::{
    CustomFieldDraft, DeleteOutcome, EditError, EntryDraft, ItemId, SecretBytes, SecretField,
    SecretString, VaultCredentials, VaultSession, VisibleText, open_vault,
};

const STANDARD: &str = "correct horse battery staple";

fn read(path: &str) -> Vec<u8> {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../shared/test-vectors");
    std::fs::read(root.join(path)).unwrap_or_else(|e| panic!("{path}: {e}"))
}

fn credentials(password: Option<&str>, key_file: Option<&str>) -> VaultCredentials {
    VaultCredentials::new(
        password.map(|p| SecretBytes::from(p.as_bytes().to_vec())),
        key_file.map(|k| SecretBytes::from(read(&format!("keyfiles/{k}")))),
    )
    .unwrap()
}

fn open(bytes: &[u8], password: Option<&str>, key_file: Option<&str>) -> VaultSession {
    VaultSession::new(open_vault(bytes, &credentials(password, key_file)).expect("opens"))
        .expect("session")
}

/// Everything a user can see or reveal, in a comparable form.
fn snapshot(session: &VaultSession) -> Vec<String> {
    let mut out: Vec<String> = session
        .groups()
        .iter()
        .map(|g| format!("group {} parent {:?} name {}", g.id, g.parent, g.name))
        .collect();
    for entry in session.entries() {
        let detail = session.entry(entry.id).unwrap();
        let password = session.reveal(entry.id, SecretField::Password).unwrap();
        let mut line = format!(
            "entry {} group {} title {:?} user {:?} url {:?} notes {:?} tags {:?} attachments {:?} password {:?}",
            entry.id,
            entry.group,
            entry.title.as_plain(),
            entry.username.as_plain(),
            entry.url.as_plain(),
            detail.notes.as_plain(),
            entry.tags,
            entry.attachment_names,
            password.expose()
        );
        for field in &detail.custom_fields {
            let value = session
                .reveal(entry.id, SecretField::Custom(&field.name))
                .unwrap();
            let _ = write!(
                line,
                " | {} {:?} {}",
                field.name,
                value.expose(),
                field.value == VisibleText::Protected
            );
        }
        out.push(line);
    }
    out.sort();
    out
}

fn save_and_reopen(
    session: &mut VaultSession,
    password: Option<&str>,
    key_file: Option<&str>,
) -> VaultSession {
    let snapshot = session.prepare_save().expect("saves");
    assert!(session.mark_saved(snapshot.generation));
    open(&snapshot.bytes, password, key_file)
}

#[test]
fn every_readable_variant_round_trips() {
    let cases: [(&str, Option<&str>, Option<&str>); 13] = [
        ("kdbx4/aes256-argon2d.kdbx", Some(STANDARD), None),
        ("kdbx4/aes256-aeskdf.kdbx", Some(STANDARD), None),
        ("kdbx4/chacha20-argon2id.kdbx", Some(STANDARD), None),
        ("kdbx4/chacha20-aeskdf-kdbx41.kdbx", Some(STANDARD), None),
        ("kdbx4/unicode-password.kdbx", Some("пароль-密码-🔑"), None),
        (
            "kdbx4/leak-marker.kdbx",
            Some("VAZIE_KEEP_SECRET_DO_NOT_LEAK_42"),
            None,
        ),
        (
            "keyfiles/vault-password-plus-keyfile.kdbx",
            Some(STANDARD),
            Some("keyfile-v2.keyx"),
        ),
        (
            "keyfiles/vault-keyfile-only.kdbx",
            None,
            Some("keyfile-v2.keyx"),
        ),
        (
            "keyfiles/vault-keyfile-xml-v1.kdbx",
            None,
            Some("keyfile-v1.key"),
        ),
        (
            "keyfiles/vault-keyfile-hashed.kdbx",
            None,
            Some("keyfile-hashed.bin"),
        ),
        ("editable/rich-aes-argon2d-gzip.kdbx", Some(STANDARD), None),
        (
            "editable/rich-chacha-aeskdf-plain-41.kdbx",
            Some(STANDARD),
            None,
        ),
        ("editable/rich-no-recycle-bin.kdbx", Some(STANDARD), None),
    ];
    for (path, password, key_file) in cases {
        let original = read(path);
        let mut session = open(&original, password, key_file);
        let before = snapshot(&session);
        let info = session.info();
        assert!(session.is_editable(), "{path}");
        let saved = session
            .prepare_save()
            .unwrap_or_else(|e| panic!("{path}: {e}"));
        assert_ne!(saved.bytes, original, "{path}: fresh seeds and IVs");
        assert!(session.mark_saved(saved.generation));
        let reopened = open(&saved.bytes, password, key_file);
        assert_eq!(snapshot(&reopened), before, "{path}");
        let again = reopened.info();
        assert_eq!(
            (again.format, again.cipher, again.kdf),
            (info.format, info.cipher, info.kdf),
            "{path}"
        );
        assert_eq!(again.fingerprint, info.fingerprint, "{path}");
        assert_eq!(again.name, info.name, "{path}");
    }
}

#[test]
fn a_vault_saved_twice_reads_back_after_each_save() {
    let mut session = open(
        &read("editable/rich-aes-argon2d-gzip.kdbx"),
        Some(STANDARD),
        None,
    );
    let first = save_and_reopen(&mut session, Some(STANDARD), None);
    let mut first = first;
    let second = save_and_reopen(&mut first, Some(STANDARD), None);
    assert_eq!(snapshot(&second), snapshot(&session));
}

fn draft(title: &str, user: &str, password: &str) -> EntryDraft {
    EntryDraft {
        title: title.to_owned(),
        username: user.to_owned(),
        password: SecretString::new(password.to_owned()),
        url: "https://new.example.com/login".to_owned(),
        notes: "multi\nline <xml> & \"quotes\"".to_owned(),
        custom_fields: vec![
            CustomFieldDraft {
                name: "PIN".to_owned(),
                value: SecretString::new("0000".to_owned()),
                protected: true,
            },
            CustomFieldDraft {
                name: "Hint".to_owned(),
                value: SecretString::new("plain hint".to_owned()),
                protected: false,
            },
        ],
        tags: vec!["new".to_owned(), "тег".to_owned()],
        favorite: false,
        expires: None,
    }
}

fn find(session: &VaultSession, title: &str) -> ItemId {
    session
        .entries()
        .find(|e| e.title.as_plain() == Some(title))
        .unwrap_or_else(|| panic!("{title}"))
        .id
}

fn group(session: &VaultSession, name: &str) -> ItemId {
    session
        .groups()
        .iter()
        .find(|g| g.name == name)
        .unwrap_or_else(|| panic!("{name}"))
        .id
}

#[test]
fn created_entries_and_groups_survive_a_reopen() {
    let mut session = open(
        &read("editable/rich-aes-argon2d-gzip.kdbx"),
        Some(STANDARD),
        None,
    );
    let fingerprint = session.info().fingerprint;
    let folder = session
        .create_group(group(&session, "Web"), "Новая папка")
        .unwrap();
    let id = session
        .create_entry(folder, &draft("Brand New", "new-user", "Gen3rated!pass"))
        .unwrap();
    assert!(session.is_dirty());
    let reopened = save_and_reopen(&mut session, Some(STANDARD), None);
    assert!(!session.is_dirty());
    assert_eq!(
        reopened.info().fingerprint,
        fingerprint,
        "a save keeps the database identity"
    );
    let entry = reopened.entry(id).unwrap();
    assert_eq!(entry.summary.group, folder);
    assert_eq!(entry.summary.title.as_plain(), Some("Brand New"));
    assert_eq!(entry.summary.tags, vec!["new", "тег"]);
    assert_eq!(
        entry.notes.as_plain(),
        Some("multi\nline <xml> & \"quotes\"")
    );
    assert_eq!(
        reopened.reveal(id, SecretField::Password).unwrap().expose(),
        "Gen3rated!pass"
    );
    assert_eq!(
        reopened
            .reveal(id, SecretField::Custom("PIN"))
            .unwrap()
            .expose(),
        "0000"
    );
    assert!(
        entry
            .custom_fields
            .iter()
            .any(|f| f.name == "PIN" && f.value == VisibleText::Protected)
    );
    assert!(
        entry
            .custom_fields
            .iter()
            .any(|f| f.name == "Hint" && f.value == VisibleText::Plain("plain hint".to_owned()))
    );
    assert_eq!(
        reopened
            .groups()
            .iter()
            .find(|g| g.id == folder)
            .unwrap()
            .name,
        "Новая папка"
    );
}

#[test]
fn an_edit_keeps_the_uuid_attachments_and_other_fields() {
    let mut session = open(
        &read("editable/rich-aes-argon2d-gzip.kdbx"),
        Some(STANDARD),
        None,
    );
    let id = find(&session, "With Attachment");
    let mut edit = draft("With Attachment", "carol-renamed", "changed-pass");
    edit.custom_fields.clear();
    session.update_entry(id, &edit).unwrap();
    let reopened = save_and_reopen(&mut session, Some(STANDARD), None);
    let entry = reopened.entry(id).unwrap();
    assert_eq!(entry.summary.username.as_plain(), Some("carol-renamed"));
    assert_eq!(entry.summary.attachment_names, vec!["notes.bin"]);
    assert_eq!(
        reopened.reveal(id, SecretField::Password).unwrap().expose(),
        "changed-pass"
    );
}

#[test]
fn removing_and_unprotecting_custom_fields() {
    let mut session = open(
        &read("editable/rich-aes-argon2d-gzip.kdbx"),
        Some(STANDARD),
        None,
    );
    let id = find(&session, "Example Mail");
    let mut edit = draft("Example Mail", "alice@example.org", "hunter2-synthetic");
    edit.custom_fields = vec![CustomFieldDraft {
        name: "Recovery Code".to_owned(),
        value: SecretString::new("RC-0000-1111".to_owned()),
        protected: false,
    }];
    edit.tags = vec!["mail".to_owned()];
    session.update_entry(id, &edit).unwrap();
    let reopened = save_and_reopen(&mut session, Some(STANDARD), None);
    let fields = reopened.entry(id).unwrap().custom_fields;
    assert_eq!(fields.len(), 1);
    assert_eq!(
        fields[0].value,
        VisibleText::Plain("RC-0000-1111".to_owned())
    );
}

#[test]
fn an_unchanged_draft_is_not_an_edit() {
    let mut session = open(
        &read("editable/rich-aes-argon2d-gzip.kdbx"),
        Some(STANDARD),
        None,
    );
    let id = find(&session, "Example Mail");
    let same = EntryDraft {
        title: "Example Mail".to_owned(),
        username: "alice@example.org".to_owned(),
        password: SecretString::new("hunter2-synthetic".to_owned()),
        url: "https://mail.example.org/login".to_owned(),
        notes: String::new(),
        custom_fields: vec![
            CustomFieldDraft {
                name: "Recovery Code".to_owned(),
                value: SecretString::new("RC-0000-1111".to_owned()),
                protected: true,
            },
            CustomFieldDraft {
                name: "Plain Note".to_owned(),
                value: SecretString::new("not secret".to_owned()),
                protected: false,
            },
        ],
        tags: vec!["mail".to_owned(), "personal".to_owned()],
        favorite: false,
        expires: None,
    };
    session.update_entry(id, &same).unwrap();
    assert!(!session.is_dirty());
}

#[test]
fn moving_renaming_and_deleting() {
    let mut session = open(
        &read("editable/rich-aes-argon2d-gzip.kdbx"),
        Some(STANDARD),
        None,
    );
    let bank = find(&session, "Bank");
    let archive = group(&session, "Archive");
    session.move_entry(bank, archive).unwrap();
    session.rename_group(archive, "Old Stuff").unwrap();
    let empty = group(&session, "Empty Group");
    assert_eq!(
        session.delete_group(empty, false, true).unwrap(),
        DeleteOutcome::Deleted
    );
    assert_eq!(
        session.delete_group(archive, false, false).unwrap_err(),
        EditError::GroupNotEmpty
    );
    let root = session.info().root_group;
    assert_eq!(
        session.delete_group(root, true, true).unwrap_err(),
        EditError::RootGroup
    );

    let reopened = save_and_reopen(&mut session, Some(STANDARD), None);
    assert_eq!(reopened.entry(bank).unwrap().summary.group, archive);
    assert_eq!(
        reopened
            .reveal(bank, SecretField::Password)
            .unwrap()
            .expose(),
        "third-password"
    );
    assert!(
        reopened
            .groups()
            .iter()
            .any(|g| g.id == archive && g.name == "Old Stuff")
    );
    assert!(!reopened.groups().iter().any(|g| g.id == empty));
}

#[test]
fn deleting_uses_the_recycle_bin_when_the_vault_has_one() {
    let mut session = open(
        &read("editable/rich-aes-argon2d-gzip.kdbx"),
        Some(STANDARD),
        None,
    );
    let bin = session
        .info()
        .recycle_bin
        .expect("fixture has a recycle bin");
    let mail = find(&session, "Example Mail");
    assert_eq!(
        session.delete_entry(mail, false).unwrap(),
        DeleteOutcome::MovedToRecycleBin
    );
    assert_eq!(session.entry(mail).unwrap().summary.group, bin);
    assert_eq!(
        session.delete_entry(mail, false).unwrap(),
        DeleteOutcome::Deleted,
        "already in the bin"
    );
    let work = group(&session, "Work");
    assert_eq!(
        session.delete_group(work, true, false).unwrap(),
        DeleteOutcome::MovedToRecycleBin
    );
    let reopened = save_and_reopen(&mut session, Some(STANDARD), None);
    assert!(reopened.entry(mail).is_err());
    assert_eq!(
        reopened
            .groups()
            .iter()
            .find(|g| g.id == work)
            .unwrap()
            .parent,
        Some(bin)
    );
    assert!(
        reopened
            .entries()
            .any(|e| e.title.as_plain() == Some("Bank"))
    );
}

#[test]
fn without_a_recycle_bin_deletion_is_permanent() {
    let mut session = open(
        &read("editable/rich-no-recycle-bin.kdbx"),
        Some(STANDARD),
        None,
    );
    assert_eq!(session.info().recycle_bin, None);
    let mail = find(&session, "Example Mail");
    assert_eq!(
        session.delete_entry(mail, false).unwrap(),
        DeleteOutcome::Deleted
    );
    let reopened = save_and_reopen(&mut session, Some(STANDARD), None);
    assert!(reopened.entry(mail).is_err());
    assert_eq!(reopened.info().recycle_bin, None);
}

#[test]
fn a_key_file_vault_saves_and_needs_its_key_file_again() {
    let mut session = open(
        &read("keyfiles/vault-password-plus-keyfile.kdbx"),
        Some(STANDARD),
        Some("keyfile-v2.keyx"),
    );
    let root = session.info().root_group;
    let id = session
        .create_entry(root, &draft("Key File Entry 2", "u", "p"))
        .unwrap();
    let saved = session.prepare_save().unwrap();
    let reopened = open(&saved.bytes, Some(STANDARD), Some("keyfile-v2.keyx"));
    assert!(reopened.entry(id).is_ok());
    assert!(open_vault(&saved.bytes, &credentials(Some(STANDARD), None)).is_err());
    assert!(
        open_vault(
            &saved.bytes,
            &credentials(Some(STANDARD), Some("keyfile-wrong.keyx"))
        )
        .is_err()
    );
}

#[test]
fn invalid_input_is_refused_without_changing_anything() {
    let mut session = open(
        &read("editable/rich-aes-argon2d-gzip.kdbx"),
        Some(STANDARD),
        None,
    );
    let root = session.info().root_group;
    let before = snapshot(&session);
    assert_eq!(
        session
            .create_entry(root, &draft("bell\u{7}", "u", "p"))
            .unwrap_err(),
        EditError::InvalidText
    );
    let mut duplicate = draft("t", "u", "p");
    duplicate.custom_fields.push(CustomFieldDraft {
        name: "PIN".to_owned(),
        value: SecretString::new(String::new()),
        protected: false,
    });
    assert_eq!(
        session.create_entry(root, &duplicate).unwrap_err(),
        EditError::InvalidFieldName
    );
    let mut reserved = draft("t", "u", "p");
    reserved.custom_fields[0].name = "Password".to_owned();
    assert_eq!(
        session.create_entry(root, &reserved).unwrap_err(),
        EditError::InvalidFieldName
    );
    assert_eq!(
        session
            .create_entry(ItemId::from_bytes([9; 16]), &draft("t", "u", "p"))
            .unwrap_err(),
        EditError::GroupNotFound
    );
    assert_eq!(snapshot(&session), before);
    assert!(!session.is_dirty());
}

#[test]
fn mark_saved_only_cleans_what_was_written() {
    let mut session = open(
        &read("editable/rich-aes-argon2d-gzip.kdbx"),
        Some(STANDARD),
        None,
    );
    let root = session.info().root_group;
    session.create_entry(root, &draft("One", "u", "p")).unwrap();
    let snapshot = session.prepare_save().unwrap();
    session.create_entry(root, &draft("Two", "u", "p")).unwrap();
    assert!(
        !session.mark_saved(snapshot.generation),
        "the second edit is still unsaved"
    );
    assert!(session.is_dirty());
    let latest = session.prepare_save().unwrap();
    assert!(session.mark_saved(latest.generation));
}

#[test]
fn favourites_are_the_favorite_tag_and_keep_history() {
    let mut session = open(
        &read("editable/rich-aes-argon2d-gzip.kdbx"),
        Some(STANDARD),
        None,
    );
    let id = find(&session, "Example Mail");
    let summary = |s: &VaultSession| s.entries().find(|e| e.id == id).unwrap();
    assert!(!summary(&session).favorite);

    session.set_favorite(id, true).unwrap();
    assert!(session.is_dirty());
    assert!(summary(&session).favorite);
    assert_eq!(summary(&session).tags, ["mail", "personal"]);

    let mut reopened = save_and_reopen(&mut session, Some(STANDARD), None);
    assert!(summary(&reopened).favorite);
    assert_eq!(summary(&reopened).tags, ["mail", "personal"]);

    reopened.set_favorite(id, true).unwrap();
    assert!(!reopened.is_dirty(), "already a favourite");
    reopened.set_favorite(id, false).unwrap();
    assert!(!summary(&reopened).favorite);
    assert_eq!(summary(&reopened).tags, ["mail", "personal"]);
}

#[test]
fn a_draft_folds_the_favourite_and_cleans_tags() {
    let mut session = open(
        &read("editable/rich-aes-argon2d-gzip.kdbx"),
        Some(STANDARD),
        None,
    );
    let personal = group(&session, "Web");
    let mut new = draft("Tagged", "t", "p");
    new.tags = vec![
        " work ".to_owned(),
        "work".to_owned(),
        String::new(),
        "favourite".to_owned(),
    ];
    let id = session.create_entry(personal, &new).unwrap();
    let reopened = save_and_reopen(&mut session, Some(STANDARD), None);
    let entry = reopened.entries().find(|e| e.id == id).unwrap();
    assert!(entry.favorite);
    assert_eq!(entry.tags, ["work"]);
}

#[test]
fn expiry_is_written_read_and_cleared() {
    let mut session = open(
        &read("editable/rich-aes-argon2d-gzip.kdbx"),
        Some(STANDARD),
        None,
    );
    let personal = group(&session, "Web");
    let mut new = draft("Expiring", "e", "p");
    new.expires = Some(1_791_244_800);
    let id = session.create_entry(personal, &new).unwrap();
    let mut reopened = save_and_reopen(&mut session, Some(STANDARD), None);
    let expires = |s: &VaultSession| s.entries().find(|e| e.id == id).unwrap().expires;
    assert_eq!(expires(&reopened), Some(1_791_244_800));

    reopened.update_entry(id, &new).unwrap();
    assert!(!reopened.is_dirty(), "same expiry is not an edit");
    new.expires = None;
    reopened.update_entry(id, &new).unwrap();
    assert!(reopened.is_dirty());
    let cleared = save_and_reopen(&mut reopened, Some(STANDARD), None);
    assert_eq!(expires(&cleared), None);
}

#[test]
fn search_matches_tags_and_tag_terms() {
    let session = open(
        &read("editable/rich-aes-argon2d-gzip.kdbx"),
        Some(STANDARD),
        None,
    );
    let titles = |query: &str| -> Vec<String> {
        let mut out: Vec<String> = session
            .search(query)
            .iter()
            .filter_map(|e| e.title.as_plain().map(str::to_owned))
            .collect();
        out.sort();
        out
    };
    assert_eq!(titles("tag:mail"), ["Example Mail"]);
    assert_eq!(titles("tag:MAIL example"), ["Example Mail"]);
    assert!(titles("tag:mai").is_empty(), "a tag term is the whole tag");
    assert!(titles("personal").contains(&"Example Mail".to_owned()));
    assert!(titles("tag:mail nothing-like-this").is_empty());
}

#[test]
fn a_group_icon_is_read_changed_and_kept() {
    use keep_core::{DEFAULT_GROUP_ICON, EditError, MAX_STANDARD_ICON};
    let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../shared/test-vectors/editable/rich-aes-argon2d-gzip.kdbx");
    let open = |bytes: &[u8]| {
        let credentials = keep_core::VaultCredentials::from_password(keep_core::SecretBytes::from(
            b"correct horse battery staple".to_vec(),
        ));
        keep_core::VaultSession::new(keep_core::open_vault(bytes, &credentials).unwrap()).unwrap()
    };
    let mut session = open(&std::fs::read(path).unwrap());
    let web = session
        .groups()
        .iter()
        .find(|g| g.name == "Web")
        .unwrap()
        .clone();
    assert_eq!(web.icon, DEFAULT_GROUP_ICON);

    session.set_group_icon(web.id, 1).unwrap();
    assert!(session.is_dirty());
    assert_eq!(
        session
            .groups()
            .iter()
            .find(|g| g.id == web.id)
            .unwrap()
            .icon,
        1
    );
    assert_eq!(
        session
            .set_group_icon(web.id, MAX_STANDARD_ICON + 1)
            .unwrap_err(),
        EditError::InvalidIcon
    );

    let snapshot = session.prepare_save().unwrap();
    assert!(session.mark_saved(snapshot.generation));
    session.set_group_icon(web.id, 1).unwrap();
    assert!(!session.is_dirty(), "the same icon again changes nothing");
    let reopened = open(&snapshot.bytes);
    assert_eq!(
        reopened
            .groups()
            .iter()
            .find(|g| g.id == web.id)
            .unwrap()
            .icon,
        1
    );
}

#[test]
fn a_pass_over_the_addresses_gives_icons_and_leaves_chosen_ones() {
    let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../shared/test-vectors/editable/rich-aes-argon2d-gzip.kdbx");
    let open = |bytes: &[u8]| {
        let credentials = keep_core::VaultCredentials::from_password(keep_core::SecretBytes::from(
            b"correct horse battery staple".to_vec(),
        ));
        keep_core::VaultSession::new(keep_core::open_vault(bytes, &credentials).unwrap()).unwrap()
    };
    let mut session = open(&std::fs::read(path).unwrap());
    let find = |s: &keep_core::VaultSession, title: &str| {
        s.entries()
            .find(|e| e.title.as_plain() == Some(title))
            .unwrap()
    };
    let chosen = find(&session, "Example Mail").id;
    session.set_entry_icon(chosen, 61).unwrap();
    let versions = session
        .entry(find(&session, "Bank").id)
        .unwrap()
        .history_count;

    let changed = session.assign_icons_by_url().unwrap();
    assert!(changed >= 2, "Bank and the entry with a non-Latin address");
    assert_eq!(
        find(&session, "Example Mail").icon,
        61,
        "an icon someone chose stays"
    );
    assert_eq!(find(&session, "Bank").icon, 37, "bank.example.com");
    assert_eq!(
        find(&session, "Юникод 🗝️ 名前").icon,
        1,
        "an address nobody knows gets the globe"
    );
    assert_eq!(find(&session, "Old Account").icon, 0, "no address, no icon");
    assert_eq!(
        session
            .entry(find(&session, "Bank").id)
            .unwrap()
            .history_count,
        versions,
        "the pass adds no history"
    );

    let snapshot = session.prepare_save().unwrap();
    assert!(session.mark_saved(snapshot.generation));
    assert_eq!(
        session.assign_icons_by_url().unwrap(),
        0,
        "a second pass finds nothing to do"
    );
    assert!(!session.is_dirty());
    assert_eq!(find(&open(&snapshot.bytes), "Bank").icon, 37);
}

#[test]
fn site_icons_are_kept_in_the_file_and_shown_on_the_entries_of_their_site() {
    let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../shared/test-vectors/editable/rich-aes-argon2d-gzip.kdbx");
    let open = |bytes: &[u8]| {
        let credentials = keep_core::VaultCredentials::from_password(keep_core::SecretBytes::from(
            b"correct horse battery staple".to_vec(),
        ));
        keep_core::VaultSession::new(keep_core::open_vault(bytes, &credentials).unwrap()).unwrap()
    };
    let mut session = open(&std::fs::read(path).unwrap());
    let find = |s: &keep_core::VaultSession, title: &str| {
        s.entries()
            .find(|e| e.title.as_plain() == Some(title))
            .unwrap()
    };
    let hosts = session.site_icon_hosts();
    assert!(hosts.contains(&"bank.example.com".to_owned()), "{hosts:?}");
    assert!(hosts.is_sorted());
    let versions = session
        .entry(find(&session, "Bank").id)
        .unwrap()
        .history_count;

    let png = [b"\x89PNG\r\n\x1a\n".as_slice(), b"made-up image"].concat();
    let icons = vec![
        ("bank.example.com".to_owned(), png.clone()),
        ("nobody.example.org".to_owned(), png.clone()),
        (hosts[0].clone(), b"<html>not an image</html>".to_vec()),
    ];
    let wanted = icons[..1].to_vec();
    assert_eq!(session.set_site_icons(&icons[2..]).unwrap(), 0);
    assert!(!session.is_dirty(), "a page instead of an image is skipped");
    assert_eq!(session.set_site_icons(&wanted).unwrap(), 1);
    let image = find(&session, "Bank").custom_icon.expect("the image");
    assert_eq!(session.custom_icon(image).as_deref(), Some(png.as_slice()));
    assert!(
        !session
            .site_icon_hosts()
            .contains(&"bank.example.com".to_owned())
    );
    assert_eq!(
        session
            .entry(find(&session, "Bank").id)
            .unwrap()
            .history_count,
        versions,
        "the pass adds no history"
    );
    assert_eq!(session.set_site_icons(&icons).unwrap_or(0), 0);

    let snapshot = session.prepare_save().unwrap();
    let mut reopened = open(&snapshot.bytes);
    let kept = find(&reopened, "Bank").custom_icon.expect("the image");
    assert_eq!(kept, image);
    assert_eq!(reopened.custom_icon(kept).as_deref(), Some(png.as_slice()));

    let group = find(&reopened, "Bank").group;
    let draft = |url: &str| keep_core::EntryDraft {
        title: "Fresh".to_owned(),
        username: String::new(),
        password: keep_core::SecretString::new(String::new()),
        url: url.to_owned(),
        notes: String::new(),
        custom_fields: Vec::new(),
        tags: Vec::new(),
        favorite: false,
        expires: None,
    };
    let fresh = reopened.create_entry(group, &draft("")).unwrap();
    assert_eq!(find(&reopened, "Fresh").icon, 0);
    assert_eq!(reopened.site_icon_host(fresh), None);
    reopened
        .update_entry(fresh, &draft("https://mail.пример.рф/in"))
        .unwrap();
    assert_eq!(
        find(&reopened, "Fresh").icon,
        19,
        "the address says what the entry is"
    );
    assert_eq!(
        reopened.site_icon_host(fresh).as_deref(),
        Some("mail.xn--e1afmkfd.xn--p1ai")
    );
    assert_eq!(
        reopened.site_icon_host(find(&reopened, "Bank").id),
        None,
        "it has its image"
    );

    let bank = find(&reopened, "Bank").id;
    reopened.set_entry_icon(bank, 37).unwrap();
    assert_eq!(
        find(&reopened, "Bank").custom_icon,
        None,
        "a chosen icon replaces the image"
    );
}
