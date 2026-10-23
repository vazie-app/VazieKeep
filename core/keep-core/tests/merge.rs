//! Merging two versions of a database (ADR 0023), through the same session API the apps use.
//!
//! The clock is a test's own so that every edit has a time that can be reasoned about: `at(n)` is
//! `n` seconds after a fixed start.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard, PoisonError};

use keep_core::{
    Access, AccessState, Argon2Settings, Cipher, CreateVaultConfig, EntryDraft, ItemId, MergeError,
    MergeOptions, MergeReport, OpenError, SecretBytes, SecretField, SecretString,
    SessionKeyMergeError, VaultCredentials, VaultSession, create_vault, open_vault, set_clock,
};

const START_MS: u64 = 1_800_000_000_000;
static NOW_MS: AtomicU64 = AtomicU64::new(START_MS);
static SERIAL: Mutex<()> = Mutex::new(());

#[allow(clippy::cast_precision_loss)]
fn clock() -> f64 {
    NOW_MS.load(Ordering::SeqCst) as f64
}

/// Holds the test's turn at the shared clock, and sets it to the start.
fn serial() -> MutexGuard<'static, ()> {
    let guard = SERIAL.lock().unwrap_or_else(PoisonError::into_inner);
    set_clock(clock);
    at(0);
    guard
}

fn at(seconds: u64) {
    NOW_MS.store(START_MS + seconds * 1000, Ordering::SeqCst);
}

fn credentials() -> VaultCredentials {
    VaultCredentials::from_password(SecretBytes::new(b"correct horse".to_vec()))
}

fn plus() -> Access {
    Access {
        state: AccessState::PlusActive,
        plus_features: true,
        needs_online_verification: false,
        lease_valid_until: None,
        observed_device_time: 0,
    }
}

fn config(name: &str) -> CreateVaultConfig {
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

fn session(bytes: &[u8]) -> VaultSession {
    VaultSession::new(open_vault(bytes, &credentials()).expect("opens")).expect("session")
}

fn draft(title: &str, password: &str) -> EntryDraft {
    EntryDraft {
        title: title.to_owned(),
        username: "user".to_owned(),
        password: SecretString::new(password.to_owned()),
        url: String::new(),
        notes: String::new(),
        tags: Vec::new(),
        favorite: false,
        expires: None,
        custom_fields: Vec::new(),
    }
}

/// A saved database with these entries (title, password) in the root group, made at time 0.
fn base_with(entries: &[(&str, &str)]) -> Vec<u8> {
    let created = create_vault(&config("Shared"), &credentials()).expect("created");
    let mut first = session(&created.bytes);
    let root = first.info().root_group;
    for (title, password) in entries {
        first.create_entry(root, &draft(title, password)).unwrap();
    }
    save(&mut first)
}

fn save(session: &mut VaultSession) -> Vec<u8> {
    let snapshot = session.prepare_save().expect("saves");
    assert!(session.mark_saved(snapshot.generation));
    snapshot.bytes
}

fn id_of(session: &VaultSession, title: &str) -> ItemId {
    session
        .entries()
        .find(|e| e.title.as_plain() == Some(title))
        .unwrap_or_else(|| panic!("no entry titled {title}"))
        .id
}

fn find(session: &VaultSession, title: &str) -> Option<ItemId> {
    session
        .entries()
        .find(|e| e.title.as_plain() == Some(title))
        .map(|e| e.id)
}

fn password_of(session: &VaultSession, id: ItemId) -> String {
    session
        .reveal(id, SecretField::Password)
        .unwrap()
        .expose()
        .to_owned()
}

fn edit_password(session: &mut VaultSession, title: &str, password: &str) {
    let id = id_of(session, title);
    session.update_entry(id, &draft(title, password)).unwrap();
}

/// Everything a person would see, comparable between two sessions: (title, password, group name).
fn picture(session: &VaultSession) -> Vec<(String, String, String)> {
    let mut all: Vec<(String, String, String)> = session
        .entries()
        .map(|e| {
            let group = session
                .groups()
                .iter()
                .find(|g| g.id == e.group)
                .map(|g| g.name.clone())
                .unwrap_or_default();
            (
                e.title.as_plain().unwrap_or_default().to_owned(),
                password_of(session, e.id),
                group,
            )
        })
        .collect();
    all.sort();
    all
}

fn merge(into: &mut VaultSession, from: &[u8]) -> MergeReport {
    let other = open_vault(from, &credentials()).expect("opens");
    into.merge(other, &MergeOptions::default()).expect("merges")
}

#[test]
fn edits_to_different_entries_combine_and_a_second_merge_changes_nothing() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0"), ("Bank", "b0")]);
    let mut mine = session(&base);
    let mut theirs = session(&base);

    at(100);
    edit_password(&mut mine, "Mail", "m-mine");
    at(200);
    edit_password(&mut theirs, "Bank", "b-theirs");
    let root = theirs.info().root_group;
    theirs.create_entry(root, &draft("Shop", "s1")).unwrap();
    let theirs_bytes = save(&mut theirs);
    save(&mut mine);

    let report = merge(&mut mine, &theirs_bytes);
    assert_eq!(report.entries_updated, 1, "{report:?}");
    assert_eq!(report.entries_added, 1);
    assert!(report.conflicts.is_empty());
    assert!(mine.is_dirty());
    assert_eq!(password_of(&mine, id_of(&mine, "Mail")), "m-mine");
    assert_eq!(password_of(&mine, id_of(&mine, "Bank")), "b-theirs");
    assert_eq!(password_of(&mine, id_of(&mine, "Shop")), "s1");

    let saved = save(&mut mine);
    let again = merge(&mut mine, &theirs_bytes);
    assert!(!again.changed(), "{again:?}");
    assert!(
        !mine.is_dirty(),
        "a merge that changed nothing leaves no edit"
    );

    // What was saved opens and holds the same.
    assert_eq!(picture(&session(&saved)), picture(&mine));
}

#[test]
fn a_newer_version_takes_over_and_the_old_one_goes_to_history() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0")]);
    let mut mine = session(&base);
    let mut theirs = session(&base);
    at(100);
    edit_password(&mut theirs, "Mail", "m1");
    at(200);
    edit_password(&mut theirs, "Mail", "m2");
    let theirs_bytes = save(&mut theirs);

    let report = merge(&mut mine, &theirs_bytes);
    assert_eq!(report.entries_updated, 1);
    assert!(
        report.conflicts.is_empty(),
        "an ordinary update is no clash"
    );
    let id = id_of(&mine, "Mail");
    assert_eq!(password_of(&mine, id), "m2");
    // m0 is the version this side had; m1 came with the other side's history.
    let versions = mine.history(id, &plus()).unwrap();
    assert_eq!(versions.len(), 2, "m0 and m1");
}

#[test]
fn a_local_version_built_on_the_other_one_is_kept_without_a_clash() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0")]);
    let mut mine = session(&base);
    let mut theirs = session(&base);
    at(100);
    edit_password(&mut theirs, "Mail", "m-theirs");
    let theirs_bytes = save(&mut theirs);

    // This side takes that version, then edits on top of it.
    merge(&mut mine, &theirs_bytes);
    at(200);
    edit_password(&mut mine, "Mail", "m-mine");
    save(&mut mine);

    let report = merge(&mut mine, &theirs_bytes);
    assert!(!report.changed(), "{report:?}");
    assert_eq!(password_of(&mine, id_of(&mine, "Mail")), "m-mine");
}

#[test]
fn an_entry_changed_on_both_sides_keeps_the_loser_as_a_visible_copy() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0")]);
    let mut mine = session(&base);
    let mut theirs = session(&base);
    at(100);
    edit_password(&mut mine, "Mail", "m-mine");
    at(200);
    edit_password(&mut theirs, "Mail", "m-theirs");
    let theirs_bytes = save(&mut theirs);
    let mine_bytes = save(&mut mine);

    let report = merge(&mut mine, &theirs_bytes);
    assert_eq!(report.conflicts.len(), 1, "{report:?}");
    assert_eq!(
        password_of(&mine, id_of(&mine, "Mail")),
        "m-theirs",
        "the later wins"
    );
    let copy = find(&mine, "Mail (conflict)").expect("the loser survives as an entry");
    assert_eq!(copy, report.conflicts[0]);
    assert_eq!(password_of(&mine, copy), "m-mine");

    // The other device merges the first one's file as it was before that merge: same winner, and
    // the copy it makes has the id the first device gave it, so there is still one copy.
    let report = merge(&mut theirs, &mine_bytes);
    assert_eq!(password_of(&theirs, id_of(&theirs, "Mail")), "m-theirs");
    assert_eq!(report.conflicts, vec![copy], "{report:?}");
    assert_eq!(report.entries_added, 0);
    assert_eq!(
        find(&theirs, "Mail (conflict)"),
        Some(copy),
        "one id on both devices"
    );
    assert_eq!(picture(&mine), picture(&theirs));

    // And again, in both directions: converged.
    let (mine_bytes, theirs_bytes) = (save(&mut mine), save(&mut theirs));
    assert!(!merge(&mut mine, &theirs_bytes).changed());
    assert!(!merge(&mut theirs, &mine_bytes).changed());
}

#[test]
fn two_edits_in_the_same_second_are_ordered_the_same_on_both_devices() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0")]);
    let mut mine = session(&base);
    let mut theirs = session(&base);
    at(100);
    edit_password(&mut mine, "Mail", "m-mine");
    edit_password(&mut theirs, "Mail", "m-theirs");
    let (mine_bytes, theirs_bytes) = (save(&mut mine), save(&mut theirs));

    merge(&mut mine, &theirs_bytes);
    merge(&mut theirs, &mine_bytes);
    assert_eq!(picture(&mine), picture(&theirs));
    assert_eq!(mine.entries().count(), 2, "the entry and one copy");
}

#[test]
fn the_copy_is_titled_in_the_callers_language() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0")]);
    let mut mine = session(&base);
    let mut theirs = session(&base);
    at(100);
    edit_password(&mut mine, "Mail", "a");
    at(200);
    edit_password(&mut theirs, "Mail", "b");
    let theirs_bytes = save(&mut theirs);

    let options = MergeOptions {
        conflict_suffix: " (конфликт)".to_owned(),
        ..MergeOptions::default()
    };
    let other = open_vault(&theirs_bytes, &credentials()).unwrap();
    mine.merge(other, &options).unwrap();
    assert!(find(&mine, "Mail (конфликт)").is_some());
}

#[test]
fn a_deletion_on_the_other_side_removes_the_entry_unless_it_was_edited_since() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0"), ("Bank", "b0")]);
    let mut mine = session(&base);
    let mut theirs = session(&base);
    at(100);
    theirs.delete_entry(id_of(&theirs, "Mail"), true).unwrap();
    theirs.delete_entry(id_of(&theirs, "Bank"), true).unwrap();
    let theirs_bytes = save(&mut theirs);
    at(200);
    edit_password(&mut mine, "Bank", "b-after-deletion");

    let report = merge(&mut mine, &theirs_bytes);
    assert_eq!(report.entries_deleted, 1, "{report:?}");
    assert!(find(&mine, "Mail").is_none());
    assert_eq!(
        password_of(&mine, id_of(&mine, "Bank")),
        "b-after-deletion",
        "an edit after the deletion keeps the entry"
    );
    assert!(report.deletion_records_added >= 2);
}

#[test]
fn an_entry_deleted_here_but_edited_there_afterwards_comes_back() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0"), ("Bank", "b0")]);
    let mut mine = session(&base);
    let mut theirs = session(&base);
    at(100);
    mine.delete_entry(id_of(&mine, "Mail"), true).unwrap();
    mine.delete_entry(id_of(&mine, "Bank"), true).unwrap();
    save(&mut mine);
    at(200);
    edit_password(&mut theirs, "Mail", "m-edited-later");
    let theirs_bytes = save(&mut theirs);

    let report = merge(&mut mine, &theirs_bytes);
    assert_eq!(report.entries_restored, 1, "{report:?}");
    assert_eq!(password_of(&mine, id_of(&mine, "Mail")), "m-edited-later");
    assert!(
        find(&mine, "Bank").is_none(),
        "untouched there, stays deleted here"
    );

    // A third device that still has the old Bank does not bring it back either.
    let mut third = session(&base);
    let merged = save(&mut mine);
    merge(&mut third, &merged);
    assert!(find(&third, "Bank").is_none());
    assert_eq!(password_of(&third, id_of(&third, "Mail")), "m-edited-later");
}

#[test]
fn moving_to_the_recycle_bin_travels() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0")]);
    let mut mine = session(&base);
    let mut theirs = session(&base);
    at(100);
    let id = id_of(&theirs, "Mail");
    theirs.delete_entry(id, false).unwrap();
    let bin = theirs.info().recycle_bin.expect("has a bin");
    let theirs_bytes = save(&mut theirs);

    let report = merge(&mut mine, &theirs_bytes);
    assert_eq!(report.entries_moved, 1, "{report:?}");
    let moved = mine.entry(id).unwrap();
    assert_eq!(moved.summary.group, bin);
    assert!(
        mine.groups().iter().any(|g| g.id == bin),
        "the bin group came with it"
    );
}

#[test]
fn new_groups_with_their_entries_arrive_and_a_later_rename_wins() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0")]);
    let mut mine = session(&base);
    let mut theirs = session(&base);
    let root = theirs.info().root_group;
    at(100);
    let group = theirs.create_group(root, "Work").unwrap();
    theirs.create_entry(group, &draft("VPN", "v1")).unwrap();
    let first = save(&mut theirs);

    let report = merge(&mut mine, &first);
    assert_eq!(report.groups_added, 1, "{report:?}");
    assert_eq!(report.entries_added, 1);
    let vpn = mine.entry(id_of(&mine, "VPN")).unwrap();
    assert_eq!(vpn.summary.group, group);
    save(&mut mine);

    at(200);
    mine.rename_group(group, "Work (mine)").unwrap();
    at(300);
    theirs.rename_group(group, "Office").unwrap();
    let second = save(&mut theirs);
    let report = merge(&mut mine, &second);
    assert_eq!(report.groups_updated, 1, "{report:?}");
    assert!(mine.groups().iter().any(|g| g.name == "Office"));
    assert!(!mine.groups().iter().any(|g| g.name == "Work (mine)"));
}

#[test]
fn a_move_between_groups_follows_the_later_one() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0")]);
    let mut mine = session(&base);
    let root = mine.info().root_group;
    at(50);
    let work = mine.create_group(root, "Work").unwrap();
    let home = mine.create_group(root, "Home").unwrap();
    let base = save(&mut mine);
    let mut theirs = session(&base);

    at(100);
    let id = id_of(&mine, "Mail");
    mine.move_entry(id, work).unwrap();
    at(200);
    theirs.move_entry(id, home).unwrap();
    let theirs_bytes = save(&mut theirs);

    let report = merge(&mut mine, &theirs_bytes);
    assert_eq!(report.entries_moved, 1, "{report:?}");
    assert_eq!(mine.entry(id).unwrap().summary.group, home);

    // Earlier on the other side: this side's place stays.
    let mut other = session(&base);
    at(100);
    other.move_entry(id, work).unwrap();
    let early = save(&mut other);
    at(300);
    mine.move_entry(id, work).unwrap();
    let report = merge(&mut mine, &early);
    assert_eq!(report.entries_moved, 0, "{report:?}");
    assert_eq!(mine.entry(id).unwrap().summary.group, work);
}

#[test]
fn attachments_come_over_with_their_bytes() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0"), ("Bank", "b0")]);
    let mut mine = session(&base);
    let mut theirs = session(&base);
    at(100);
    let id = id_of(&theirs, "Mail");
    theirs
        .add_attachment(id, "key.txt", b"secret bytes", &plus())
        .unwrap();
    let theirs_bytes = save(&mut theirs);
    // This side has an attachment of its own on another entry, so the pools differ.
    at(110);
    let bank = id_of(&mine, "Bank");
    mine.add_attachment(bank, "note.txt", b"mine", &plus())
        .unwrap();
    save(&mut mine);

    let report = merge(&mut mine, &theirs_bytes);
    assert_eq!(report.entries_updated, 1, "{report:?}");
    assert_eq!(
        mine.attachment(id, "key.txt").unwrap().expose(),
        b"secret bytes"
    );
    assert_eq!(mine.attachment(bank, "note.txt").unwrap().expose(), b"mine");
    // Saved and reopened, both are still where they were.
    let reopened = session(&save(&mut mine));
    assert_eq!(
        reopened.attachment(id, "key.txt").unwrap().expose(),
        b"secret bytes"
    );
    assert_eq!(
        reopened.attachment(bank, "note.txt").unwrap().expose(),
        b"mine"
    );
}

#[test]
fn a_removed_attachment_leaves_no_orphan_in_the_pool() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0")]);
    let mut mine = session(&base);
    let id = id_of(&mine, "Mail");
    at(50);
    mine.add_attachment(id, "a.txt", b"first", &plus()).unwrap();
    let base = save(&mut mine);
    let mut theirs = session(&base);
    at(100);
    theirs.remove_attachment(id, "a.txt").unwrap();
    theirs
        .add_attachment(id, "b.txt", b"second", &plus())
        .unwrap();
    let theirs_bytes = save(&mut theirs);

    merge(&mut mine, &theirs_bytes);
    assert_eq!(mine.attachment(id, "b.txt").unwrap().expose(), b"second");
    assert_eq!(
        mine.entry(id).unwrap().summary.attachment_names,
        vec!["b.txt".to_owned()]
    );
}

#[test]
fn the_history_stays_within_its_limit() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0")]);
    let mut mine = session(&base);
    let mut theirs = session(&base);
    for step in 1..=8 {
        at(100 + step);
        edit_password(&mut mine, "Mail", &format!("mine-{step}"));
    }
    for step in 1..=8 {
        at(200 + step);
        edit_password(&mut theirs, "Mail", &format!("theirs-{step}"));
    }
    let theirs_bytes = save(&mut theirs);
    merge(&mut mine, &theirs_bytes);
    let id = id_of(&mine, "Mail");
    assert_eq!(password_of(&mine, id), "theirs-8");
    assert!(mine.history(id, &plus()).unwrap().len() <= 10);
}

#[test]
fn databases_that_are_not_versions_of_one_vault_are_refused_unless_allowed() {
    let _turn = serial();
    let mut mine = session(&base_with(&[("Mail", "m0")]));
    let other = base_with(&[("Bank", "b0")]);

    let refused = mine.merge(
        open_vault(&other, &credentials()).unwrap(),
        &MergeOptions::default(),
    );
    assert_eq!(refused.unwrap_err(), MergeError::DifferentVault);
    assert!(!mine.is_dirty());
    assert!(find(&mine, "Bank").is_none());

    let options = MergeOptions {
        allow_unrelated: true,
        ..MergeOptions::default()
    };
    let report = mine
        .merge(open_vault(&other, &credentials()).unwrap(), &options)
        .unwrap();
    assert_eq!(report.entries_added, 1);
    assert!(find(&mine, "Bank").is_some());
    assert!(find(&mine, "Mail").is_some());
}

#[test]
fn the_session_key_merges_a_file_another_device_saved_without_a_password() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0"), ("Bank", "b0")]);
    let mut mine = session(&base);
    let mut theirs = session(&base);

    at(100);
    edit_password(&mut mine, "Mail", "m-mine");
    at(200);
    edit_password(&mut theirs, "Bank", "b-theirs");
    let root = theirs.info().root_group;
    theirs.create_entry(root, &draft("Shop", "s1")).unwrap();
    let theirs_bytes = save(&mut theirs);

    let report = mine
        .merge_with_session_key(&theirs_bytes, &MergeOptions::default())
        .expect("the session's own key opens it");
    assert_eq!(report.entries_updated, 1, "{report:?}");
    assert_eq!(report.entries_added, 1);
    assert!(mine.is_dirty());
    assert_eq!(password_of(&mine, id_of(&mine, "Mail")), "m-mine");
    assert_eq!(password_of(&mine, id_of(&mine, "Bank")), "b-theirs");
    assert_eq!(password_of(&mine, id_of(&mine, "Shop")), "s1");

    // The same result as opening the file with the password.
    let mut with_password = session(&base);
    at(100);
    edit_password(&mut with_password, "Mail", "m-mine");
    merge(&mut with_password, &theirs_bytes);
    assert_eq!(picture(&mine), picture(&with_password));
}

#[test]
fn the_session_key_does_not_fit_a_file_with_another_key_and_changes_nothing() {
    let _turn = serial();
    let base = base_with(&[("Mail", "m0")]);
    let mut mine = session(&base);
    let other = create_vault(
        &config("Shared"),
        &VaultCredentials::from_password(SecretBytes::new(b"another password".to_vec())),
    )
    .expect("created");

    let error = mine
        .merge_with_session_key(&other.bytes, &MergeOptions::default())
        .expect_err("another key");
    assert_eq!(
        error,
        SessionKeyMergeError::Open(OpenError::InvalidCredentials)
    );
    assert!(!mine.is_dirty());
    assert_eq!(picture(&mine), picture(&session(&base)));
}

#[test]
fn the_session_key_merge_refuses_a_file_that_is_not_a_vault() {
    let _turn = serial();
    let mut mine = session(&base_with(&[("Mail", "m0")]));
    let error = mine
        .merge_with_session_key(b"not a database", &MergeOptions::default())
        .expect_err("not a vault");
    assert!(matches!(error, SessionKeyMergeError::Open(_)), "{error:?}");
    assert!(!mine.is_dirty());
}
