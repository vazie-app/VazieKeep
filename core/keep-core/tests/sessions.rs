use std::path::PathBuf;
use std::sync::Arc;
use std::thread;

use keep_core::{
    ItemId, SecretBytes, SecretField, SessionError, SessionId, VaultCredentials, VaultSession,
    VaultSessionStore, VisibleText, open_vault,
};

const MARKER: &str = "VAZIE_KEEP_SECRET_DO_NOT_LEAK_42";
const STANDARD: &str = "correct horse battery staple";

fn vector(name: &str) -> Vec<u8> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../shared/test-vectors/kdbx4")
        .join(name);
    std::fs::read(path).expect("vector file")
}

fn session(name: &str, password: &str) -> VaultSession {
    let credentials =
        VaultCredentials::from_password(SecretBytes::from(password.as_bytes().to_vec()));
    let vault = open_vault(&vector(name), &credentials).expect("vault opens");
    VaultSession::new(vault).expect("session builds")
}

fn full_session() -> VaultSession {
    session("aes256-argon2d.kdbx", STANDARD)
}

fn entry_id(session: &VaultSession, title: &str) -> ItemId {
    session
        .entries()
        .find(|e| e.title.as_plain() == Some(title))
        .map(|e| e.id)
        .expect("entry exists")
}

#[test]
fn open_query_close_query() {
    let store = VaultSessionStore::default();
    let id = store.insert(full_session()).unwrap();
    assert_eq!(store.get(id).unwrap().read().unwrap().info().entry_count, 6);
    assert!(store.close(id));
    assert_eq!(store.get(id).unwrap_err(), SessionError::SessionClosed);
    assert!(!store.close(id));
    assert!(store.is_empty());
}

#[test]
fn unknown_and_malformed_handles_are_errors_not_panics() {
    let store = VaultSessionStore::default();
    let random = SessionId::parse("0123456789abcdef0123456789abcdef").unwrap();
    assert_eq!(
        store.get(random).unwrap_err(),
        SessionError::SessionNotFound
    );
    for malformed in [
        "",
        "1",
        "0123456789ABCDEF0123456789ABCDEF",
        "zz23456789abcdef0123456789abcdef",
        "0123456789abcdef0123456789abcdef00",
        "🔑🔑🔑🔑🔑🔑🔑🔑",
    ] {
        assert_eq!(
            SessionId::parse(malformed).unwrap_err(),
            SessionError::InvalidHandle,
            "{malformed}"
        );
    }
    let id = store.insert(full_session()).unwrap();
    assert_eq!(SessionId::parse(&id.to_string()), Ok(id));
}

#[test]
fn entry_ids_from_another_session_are_not_found() {
    let store = VaultSessionStore::default();
    let a = store.insert(full_session()).unwrap();
    let b = store
        .insert(session("unicode-password.kdbx", "пароль-密码-🔑"))
        .unwrap();
    let foreign = entry_id(&store.get(a).unwrap().read().unwrap(), "Example Mail");
    assert_eq!(
        store
            .get(b)
            .unwrap()
            .read()
            .unwrap()
            .reveal(foreign, SecretField::Password)
            .unwrap_err(),
        SessionError::EntryNotFound
    );
}

#[test]
fn multiple_sessions_are_independent() {
    let store = VaultSessionStore::default();
    let a = store.insert(full_session()).unwrap();
    let b = store
        .insert(session("chacha20-aeskdf-kdbx41.kdbx", STANDARD))
        .unwrap();
    assert_ne!(a, b);
    assert_eq!(
        store.get(a).unwrap().read().unwrap().info().name,
        "Synthetic Test Vault"
    );
    assert_eq!(store.get(b).unwrap().read().unwrap().info().name, "Minimal");
    store.close(a);
    assert_eq!(store.get(a).unwrap_err(), SessionError::SessionClosed);
    let b_session_shared = store.get(b).unwrap();
    let b_session = b_session_shared.read().unwrap();
    let only = entry_id(&b_session, "Only Entry");
    assert_eq!(
        b_session
            .reveal(only, SecretField::Password)
            .unwrap()
            .expose(),
        "correct horse battery staple!"
    );
}

#[test]
fn summaries_never_carry_secret_values() {
    let session = session("leak-marker.kdbx", MARKER);
    let summary = session.entries().next().unwrap();
    assert!(summary.has_password);
    assert!(summary.has_protected_fields());
    let detail = session.entry(summary.id).unwrap();
    assert_eq!(detail.custom_fields[0].value, VisibleText::Protected);
    for text in [
        format!("{session:?}"),
        format!("{:?}", session.info()),
        format!("{:?}", session.groups()),
        format!("{summary:?}"),
        format!("{detail:?}"),
        format!("{:?}", session.search("marker")),
    ] {
        assert!(!text.contains(MARKER), "leak in {text}");
    }
    let plain_values: Vec<String> = session
        .entries()
        .flat_map(|e| [e.title, e.username, e.url])
        .filter_map(|v| v.as_plain().map(str::to_owned))
        .collect();
    assert!(plain_values.iter().all(|v| !v.contains(MARKER)));
}

#[test]
fn secrets_come_only_from_reveal() {
    let session = session("leak-marker.kdbx", MARKER);
    let id = session.entries().next().unwrap().id;
    let password = session.reveal(id, SecretField::Password).unwrap();
    assert_eq!(password.expose(), MARKER);
    assert!(!format!("{password:?}").contains(MARKER));
    let field = session
        .reveal(id, SecretField::from_kdbx_key("Marker Field"))
        .unwrap();
    assert_eq!(field.expose(), MARKER);
    assert_eq!(
        session
            .reveal(id, SecretField::Custom("Missing"))
            .unwrap_err(),
        SessionError::FieldNotFound
    );
    let missing = ItemId::from_bytes([0xee; 16]);
    assert_eq!(
        session.reveal(missing, SecretField::Password).unwrap_err(),
        SessionError::EntryNotFound
    );
    for error in [
        SessionError::FieldNotFound,
        SessionError::EntryNotFound,
        SessionError::SessionClosed,
    ] {
        assert!(!format!("{error} {error:?}").contains(MARKER));
    }
}

#[test]
fn search_matches_title_username_url_but_never_secrets() {
    let session = full_session();
    let titles = |query: &str| -> Vec<String> {
        session
            .search(query)
            .into_iter()
            .filter_map(|e| e.title.as_plain().map(str::to_owned))
            .collect()
    };
    assert_eq!(titles("MAIL.example"), ["Example Mail"]);
    assert_eq!(titles("alice@"), ["Example Mail"]);
    assert_eq!(titles("юникод"), ["Юникод 🗝️ 名前"]);
    assert!(titles("hunter2").is_empty());
    assert!(titles("RC-0000").is_empty());
    assert_eq!(session.search("  ").len(), 6);
}

#[test]
fn groups_keep_structure_and_stable_ids() {
    let session = full_session();
    let info = session.info();
    let groups = session.groups();
    assert_eq!(groups[0].id, info.root_group);
    assert_eq!(groups[0].parent, None);
    let web = groups.iter().find(|g| g.name == "Web").unwrap();
    let work = groups.iter().find(|g| g.name == "Work").unwrap();
    assert_eq!(web.parent, Some(info.root_group));
    assert_eq!(work.parent, Some(web.id));
    let again = full_session();
    assert_eq!(
        entry_id(&again, "Example Mail"),
        entry_id(&session, "Example Mail")
    );
}

#[test]
fn concurrent_readers_share_one_session() {
    let store = Arc::new(VaultSessionStore::default());
    let id = store.insert(full_session()).unwrap();
    let mail = entry_id(&store.get(id).unwrap().read().unwrap(), "Example Mail");
    let workers: Vec<_> = (0..8)
        .map(|_| {
            let store = Arc::clone(&store);
            thread::spawn(move || {
                for _ in 0..200 {
                    let session_shared = store.get(id).unwrap();
                    let session = session_shared.read().unwrap();
                    assert_eq!(session.entries().count(), 6);
                    let secret = session.reveal(mail, SecretField::Password).unwrap();
                    assert_eq!(secret.expose(), "hunter2-synthetic");
                }
            })
        })
        .collect();
    for worker in workers {
        worker.join().unwrap();
    }
}

#[test]
fn close_while_readers_hold_the_session_is_safe() {
    let store = Arc::new(VaultSessionStore::default());
    let id = store.insert(full_session()).unwrap();
    let held_shared = store.get(id).unwrap();
    let held = held_shared.read().unwrap();
    let closer = {
        let store = Arc::clone(&store);
        thread::spawn(move || store.close(id))
    };
    assert!(closer.join().unwrap());
    assert_eq!(held.entries().count(), 6);
    drop(held);
    assert_eq!(store.get(id).unwrap_err(), SessionError::SessionClosed);
}

#[test]
fn many_open_close_cycles_leave_no_sessions() {
    let store = VaultSessionStore::new(4);
    let template = vector("chacha20-aeskdf-kdbx41.kdbx");
    for _ in 0..300 {
        let credentials =
            VaultCredentials::from_password(SecretBytes::from(STANDARD.as_bytes().to_vec()));
        let vault = open_vault(&template, &credentials).unwrap();
        let id = store.insert(VaultSession::new(vault).unwrap()).unwrap();
        assert_eq!(store.len(), 1);
        assert!(store.close(id));
    }
    assert!(store.is_empty());
}

#[test]
fn session_limit_and_close_all() {
    let store = VaultSessionStore::new(2);
    let first = store.insert(full_session()).unwrap();
    store.insert(full_session()).unwrap();
    assert!(!store.has_capacity());
    assert_eq!(
        store.insert(full_session()).unwrap_err(),
        SessionError::TooManySessions
    );
    assert_eq!(store.close_all(), 2);
    assert_eq!(store.get(first).unwrap_err(), SessionError::SessionClosed);
    assert!(store.insert(full_session()).is_ok());
}

#[test]
fn fingerprint_follows_the_root_group_only() {
    let credentials =
        VaultCredentials::from_password(SecretBytes::from(STANDARD.as_bytes().to_vec()));
    let open = || open_vault(&vector("aes256-argon2d.kdbx"), &credentials).unwrap();
    let a = VaultSession::new(open()).unwrap();
    let same_database_resaved = session("chacha20-argon2id.kdbx", STANDARD);
    let mut other = open();
    other.root.id = ItemId::from_bytes([0x5a; 16]);
    let other = VaultSession::new(other).unwrap();

    assert_eq!(a.fingerprint().len(), 64);
    assert_eq!(a.info().fingerprint, a.fingerprint());
    assert_eq!(a.fingerprint(), same_database_resaved.fingerprint());
    assert_ne!(a.fingerprint(), other.fingerprint());
    let root_hex: String = a.info().root_group.to_string().replace('-', "");
    assert!(!a.fingerprint().contains(&root_hex));
}

#[test]
fn unlock_material_is_retained_only_when_asked_and_never_formatted() {
    let credentials =
        VaultCredentials::from_password(SecretBytes::from(MARKER.as_bytes().to_vec()));
    let vault = open_vault(&vector("leak-marker.kdbx"), &credentials).unwrap();
    let mut session = VaultSession::new(vault).unwrap();
    assert!(session.unlock_material().is_none());
    session.retain_unlock_material(credentials.unlock_material().unwrap());
    let material = session.unlock_material().unwrap();
    let reopened = keep_core::UnlockMaterial::decode(material.encode().expose()).unwrap();
    let again = VaultCredentials::from_unlock_material(&reopened, None).unwrap();
    assert!(open_vault(&vector("leak-marker.kdbx"), &again).is_ok());
    assert!(!format!("{session:?}{material:?}").contains(MARKER));
}
