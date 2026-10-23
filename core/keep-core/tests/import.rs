//! Exports of other password managers, read and taken into a vault. Every file here is made up.

use std::io::Write;
use std::path::PathBuf;

use flate2::Compression;
use flate2::write::DeflateEncoder;
use keep_core::{
    Access, AccessState, ImportError, ImportSource, SecretBytes, SecretField, VaultCredentials,
    VaultSession, open_vault, parse_import,
};

fn rich() -> VaultSession {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../shared/test-vectors/editable/rich-aes-argon2d-gzip.kdbx");
    open(&std::fs::read(path).unwrap())
}

fn open(bytes: &[u8]) -> VaultSession {
    let credentials = VaultCredentials::from_password(SecretBytes::from(
        b"correct horse battery staple".to_vec(),
    ));
    VaultSession::new(open_vault(bytes, &credentials).expect("opens")).expect("session")
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

/// A one-member ZIP, stored or deflated, the way a `.1pux` file holds `export.data`.
fn zip(name: &str, data: &[u8], deflate: bool) -> Vec<u8> {
    let packed = if deflate {
        let mut encoder = DeflateEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(data).unwrap();
        encoder.finish().unwrap()
    } else {
        data.to_vec()
    };
    let method: u16 = if deflate { 8 } else { 0 };
    let size = u32::try_from(data.len()).unwrap();
    let packed_size = u32::try_from(packed.len()).unwrap();
    let name_length = u16::try_from(name.len()).unwrap();
    let mut out = Vec::new();
    out.extend_from_slice(&[0x50, 0x4b, 0x03, 0x04, 20, 0, 0, 0]);
    out.extend_from_slice(&method.to_le_bytes());
    out.extend_from_slice(&[0; 8]);
    out.extend_from_slice(&packed_size.to_le_bytes());
    out.extend_from_slice(&size.to_le_bytes());
    out.extend_from_slice(&name_length.to_le_bytes());
    out.extend_from_slice(&[0, 0]);
    out.extend_from_slice(name.as_bytes());
    out.extend_from_slice(&packed);
    let directory = u32::try_from(out.len()).unwrap();
    out.extend_from_slice(&[0x50, 0x4b, 0x01, 0x02, 20, 0, 20, 0, 0, 0]);
    out.extend_from_slice(&method.to_le_bytes());
    out.extend_from_slice(&[0; 8]);
    out.extend_from_slice(&packed_size.to_le_bytes());
    out.extend_from_slice(&size.to_le_bytes());
    out.extend_from_slice(&name_length.to_le_bytes());
    out.extend_from_slice(&[0; 12]);
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(name.as_bytes());
    let directory_size = u32::try_from(out.len()).unwrap() - directory;
    out.extend_from_slice(&[0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 1, 0, 1, 0]);
    out.extend_from_slice(&directory_size.to_le_bytes());
    out.extend_from_slice(&directory.to_le_bytes());
    out.extend_from_slice(&[0, 0]);
    out
}

const SECRET: &str = "JBSWY3DPEHPK3PXP";

#[test]
fn a_browser_csv_is_read_by_its_header() {
    let csv = "name,url,username,password,note\r\nExample,https://example.com,alice,\"p,a\"\"ss\nword\",\"line one\nline two\"\r\n,,,,\r\nSecond,https://second.example,bob,hunter2,\r\n";
    let plan = parse_import(csv.as_bytes()).unwrap();
    assert_eq!(plan.source, ImportSource::Csv);
    assert_eq!(plan.entries.len(), 2);
    let first = &plan.entries[0];
    assert_eq!(first.title, "Example");
    assert_eq!(first.username, "alice");
    assert_eq!(
        first.password.expose(),
        "p,a\"ssword",
        "a password is one line"
    );
    assert_eq!(first.url, "https://example.com");
    assert_eq!(first.notes, "line one\nline two");
    assert!(first.fields.is_empty() && first.folder.is_empty());
}

#[test]
fn a_keepassxc_csv_keeps_groups_codes_and_other_columns() {
    let csv = format!(
        "\u{feff}\"Group\",\"Title\",\"Username\",\"Password\",\"URL\",\"Notes\",\"TOTP\",\"Icon\",\"Last Modified\",\"Created\",\"PIN\"\n\"Root/Web/Work\",\"Bank\",\"bob\",\"s3cret\",\"https://bank.example\",\"\",\"{SECRET}\",\"0\",\"2026-01-01\",\"2025-01-01\",\"4321\"\n"
    );
    let plan = parse_import(csv.as_bytes()).unwrap();
    let entry = &plan.entries[0];
    assert_eq!(entry.folder, ["Web", "Work"]);
    assert!(entry.has_totp());
    let otp = entry.fields.iter().find(|f| f.name == "otp").unwrap();
    assert!(otp.protected && otp.value.expose().starts_with("otpauth://totp/"));
    let names: Vec<&str> = entry.fields.iter().map(|f| f.name.as_str()).collect();
    assert_eq!(names, ["otp", "pin"], "bookkeeping columns are left out");
}

#[test]
fn a_semicolon_separated_file_is_read_too() {
    let csv = "Title;Username;Password;URL\nShop;carol;pw;https://shop.example\n";
    let plan = parse_import(csv.as_bytes()).unwrap();
    assert_eq!(plan.entries[0].username, "carol");
}

#[test]
fn a_one_password_csv_is_recognised() {
    let csv = "Title,Url,Username,Password,OTPAuth,Favorite,Archived,Tags,Notes\nMail,https://mail.example,dana,pw1,otpauth://totp/Mail:dana?secret=JBSWY3DPEHPK3PXP&issuer=Mail,true,false,work;mail,hello\nOld,https://old.example,dana,pw2,,false,true,,\n";
    let plan = parse_import(csv.as_bytes()).unwrap();
    assert_eq!(plan.source, ImportSource::OnePasswordCsv);
    let mail = &plan.entries[0];
    assert!(mail.favorite && mail.has_totp());
    assert_eq!(mail.tags, ["work", "mail"]);
    assert_eq!(plan.entries[1].folder, ["Archive"]);
}

#[test]
fn a_bitwarden_csv_is_recognised() {
    let csv = "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\nSocial/Chat,1,login,Chat,,\"Recovery: abc\nPIN: 1234\",0,\"https://chat.example,https://app.chat.example\",erin,pw,JBSWY3DPEHPK3PXP\n,,note,Wifi,the password is on the router,,,,,,\n";
    let plan = parse_import(csv.as_bytes()).unwrap();
    assert_eq!(plan.source, ImportSource::BitwardenCsv);
    let chat = &plan.entries[0];
    assert_eq!(chat.folder, ["Social", "Chat"]);
    assert_eq!(chat.url, "https://chat.example");
    let names: Vec<&str> = chat.fields.iter().map(|f| f.name.as_str()).collect();
    assert_eq!(names, ["otp", "URL 2", "Recovery", "PIN"]);
    assert!(chat.favorite);
    assert_eq!(plan.entries[1].notes, "the password is on the router");
}

const BITWARDEN: &str = r#"{
  "encrypted": false,
  "folders": [{"id": "f1", "name": "Work/Servers"}],
  "items": [
    {"type": 1, "name": "Server", "notes": "ssh only", "favorite": true, "folderId": "f1",
     "login": {"username": "root", "password": "t0p\u0007secret", "totp": "otpauth://totp/Server?secret=JBSWY3DPEHPK3PXP",
               "uris": [{"uri": "https://a.example"}, {"uri": "https://b.example"}]},
     "fields": [{"name": "Port", "value": "22", "type": 0}, {"name": "Key", "value": "hidden", "type": 1},
                {"name": "Port", "value": "2222", "type": 0}, {"name": "Linked", "value": null, "type": 3}]},
    {"type": 2, "name": "Note", "notes": "remember", "favorite": false, "folderId": null},
    {"type": 3, "name": "Card", "card": {"cardholderName": "A B", "number": "4111111111111111", "code": "123", "expMonth": "1"}},
    {"type": 1, "name": "Gone", "deletedDate": "2026-01-01T00:00:00Z", "login": {"username": "x", "password": "y"}},
    {"type": 1, "name": "", "login": {}}
  ]
}"#;

#[test]
fn a_bitwarden_json_export_keeps_folders_fields_and_cards() {
    let plan = parse_import(BITWARDEN.as_bytes()).unwrap();
    assert_eq!(plan.source, ImportSource::BitwardenJson);
    assert_eq!(plan.entries.len(), 3);
    assert_eq!(plan.skipped, 2, "the deleted item and the empty one");
    let server = &plan.entries[0];
    assert_eq!(server.folder, ["Work", "Servers"]);
    assert_eq!(
        server.password.expose(),
        "t0psecret",
        "a control character can't live in a vault"
    );
    assert_eq!(server.url, "https://a.example");
    let fields: Vec<(&str, bool)> = server
        .fields
        .iter()
        .map(|f| (f.name.as_str(), f.protected))
        .collect();
    assert_eq!(
        fields,
        [
            ("otp", true),
            ("URL 2", false),
            ("Port", false),
            ("Key", true),
            ("Port (2)", false)
        ]
    );
    let card = &plan.entries[2];
    let number = card.fields.iter().find(|f| f.name == "number").unwrap();
    assert!(number.protected);
    assert!(
        card.fields
            .iter()
            .find(|f| f.name == "code")
            .unwrap()
            .protected
    );
    assert!(
        !card
            .fields
            .iter()
            .find(|f| f.name == "cardholderName")
            .unwrap()
            .protected
    );
}

#[test]
fn an_encrypted_bitwarden_export_is_refused() {
    let encrypted = r#"{"encrypted": true, "items": [{"name": "2.abc|def|ghi"}]}"#;
    assert_eq!(
        parse_import(encrypted.as_bytes()).unwrap_err(),
        ImportError::Encrypted
    );
    let protected = r#"{"encrypted": true, "passwordProtected": true, "data": "x"}"#;
    assert_eq!(
        parse_import(protected.as_bytes()).unwrap_err(),
        ImportError::Encrypted
    );
}

const ONE_PASSWORD: &str = r#"{"accounts": [{"attrs": {"name": "A"}, "vaults": [
  {"attrs": {"name": "Personal"}, "items": [
    {"uuid": "1", "favIndex": 1, "state": "active", "categoryUuid": "001",
     "overview": {"title": "Shop", "url": "https://shop.example", "urls": [{"label": "website", "url": "https://shop.example"}, {"url": "https://m.shop.example"}], "tags": ["shopping"]},
     "details": {"loginFields": [{"value": "frank", "name": "username", "designation": "username"}, {"value": "pw-1", "name": "password", "designation": "password"}],
                 "notesPlain": "member since 2020",
                 "sections": [{"title": "Security", "fields": [
                    {"title": "one-time password", "id": "TOTP_1", "value": {"totp": "otpauth://totp/Shop?secret=JBSWY3DPEHPK3PXP"}},
                    {"title": "PIN", "id": "p", "value": {"concealed": "9876"}},
                    {"title": "since", "id": "d", "value": {"date": 1577836800}},
                    {"title": "doc", "id": "f", "value": {"file": {"fileName": "a.pdf"}}}]}]}},
    {"uuid": "2", "favIndex": 0, "state": "archived", "overview": {"title": "Old forum"}, "details": {"password": "only-a-password"}}
  ]},
  {"attrs": {"name": "Work"}, "items": [
    {"uuid": "3", "favIndex": 0, "state": "active", "overview": {"title": "VPN"}, "details": {"loginFields": [{"value": "gina", "designation": "username"}, {"value": "pw-3", "designation": "password"}]}}
  ]}
]}]}"#;

#[test]
fn a_1pux_export_is_read_from_its_archive() {
    for deflate in [false, true] {
        let plan = parse_import(&zip("export.data", ONE_PASSWORD.as_bytes(), deflate)).unwrap();
        assert_eq!(plan.source, ImportSource::OnePassword1pux);
        assert_eq!(plan.entries.len(), 3);
        let shop = &plan.entries[0];
        assert_eq!(
            (shop.username.as_str(), shop.password.expose()),
            ("frank", "pw-1")
        );
        assert_eq!(shop.folder, ["Personal"]);
        assert!(shop.favorite && shop.has_totp());
        assert_eq!(shop.tags, ["shopping"]);
        let fields: Vec<(&str, bool)> = shop
            .fields
            .iter()
            .map(|f| (f.name.as_str(), f.protected))
            .collect();
        assert_eq!(
            fields,
            [
                ("otp", true),
                ("URL 2", false),
                ("Security: PIN", true),
                ("Security: since", false)
            ]
        );
        let old = &plan.entries[1];
        assert_eq!(old.folder, ["Personal", "Archive"]);
        assert_eq!(old.password.expose(), "only-a-password");
        assert_eq!(plan.entries[2].folder, ["Work"]);
    }
}

#[test]
fn files_that_are_not_exports_are_refused() {
    assert_eq!(parse_import(b"").unwrap_err(), ImportError::Empty);
    assert_eq!(parse_import(b"  \n ").unwrap_err(), ImportError::Empty);
    assert_eq!(
        parse_import(b"city,population\nParis,2000000\n").unwrap_err(),
        ImportError::Unrecognized
    );
    assert_eq!(
        parse_import(b"name,url,username,password\n").unwrap_err(),
        ImportError::Empty
    );
    assert_eq!(
        parse_import(b"name,password\n\"open,pw\n").unwrap_err(),
        ImportError::Malformed
    );
    assert_eq!(
        parse_import(br#"{"hello": 1}"#).unwrap_err(),
        ImportError::Unrecognized
    );
    assert_eq!(
        parse_import(br#"{"items": ["#).unwrap_err(),
        ImportError::Malformed
    );
    assert_eq!(
        parse_import(&zip("other.txt", b"x", false)).unwrap_err(),
        ImportError::Unrecognized
    );
    assert_eq!(
        parse_import(&zip("export.data", b"not json", true)).unwrap_err(),
        ImportError::Malformed
    );
    assert_eq!(
        parse_import(b"PK\x03\x04 truncated").unwrap_err(),
        ImportError::Unrecognized
    );
    assert_eq!(
        parse_import(&[0xff, 0xfe, 0x00, 0x41]).unwrap_err(),
        ImportError::Unrecognized
    );
}

#[test]
fn an_import_lands_in_the_vault_and_survives_a_save() {
    let mut session = rich();
    let root = session.info().root_group;
    let before = session.entries().count();
    let web = session
        .groups()
        .iter()
        .find(|g| g.name == "Web")
        .unwrap()
        .id;
    let plan = parse_import(BITWARDEN.as_bytes()).unwrap();

    let outcome = session.import(root, &plan).unwrap();
    assert_eq!(
        (outcome.entries, outcome.groups),
        (3, 2),
        "Work and Work/Servers are new"
    );
    assert!(session.is_dirty());
    assert_eq!(session.entries().count(), before + 3);

    let csv = "Group,Title,Username,Password,URL\nWeb/Work,Imported,zed,pw-z,https://z.example\nWeb,Top,yan,pw-y,\n";
    let second = session
        .import(root, &parse_import(csv.as_bytes()).unwrap())
        .unwrap();
    assert_eq!(second.groups, 0, "folders that already exist are reused");

    let snapshot = session.prepare_save().expect("saves");
    let reopened = open(&snapshot.bytes);
    let server = reopened
        .entries()
        .find(|e| e.title.as_plain() == Some("Server"))
        .unwrap();
    assert!(server.favorite && server.has_totp);
    let servers = reopened
        .groups()
        .iter()
        .find(|g| g.name == "Servers")
        .unwrap()
        .clone();
    assert_eq!(server.group, servers.id);
    assert_eq!(
        reopened
            .reveal(server.id, SecretField::Password)
            .unwrap()
            .expose(),
        "t0psecret"
    );
    assert_eq!(
        reopened
            .reveal(server.id, SecretField::Custom("Key"))
            .unwrap()
            .expose(),
        "hidden"
    );
    assert!(reopened.totp(server.id, 1_700_000_000, &plus()).is_ok());
    let top = reopened
        .entries()
        .find(|e| e.title.as_plain() == Some("Top"))
        .unwrap();
    assert_eq!(top.group, web);
}

#[test]
fn an_import_into_a_group_that_is_gone_changes_nothing() {
    let mut session = rich();
    let plan = parse_import(BITWARDEN.as_bytes()).unwrap();
    let before = session.entries().count();
    assert!(
        session
            .import(keep_core::ItemId::from_bytes([9; 16]), &plan)
            .is_err()
    );
    assert_eq!(session.entries().count(), before);
    assert!(!session.is_dirty());
}
