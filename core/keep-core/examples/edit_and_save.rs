//! Interop helper: opens a vault, makes a fixed set of edits, and writes the result through the
//! KDBX writer, so an independent implementation can check the file.
//!
//!     cargo run -p keep-core --example edit_and_save -- <in.kdbx> <out.kdbx> <password>

use keep_core::{
    Access, AccessState, CustomFieldDraft, EntryDraft, SecretBytes, SecretString, VaultCredentials,
    VaultSession, open_vault,
};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let [_, input, output, password] = args.as_slice() else {
        eprintln!("usage: edit_and_save <in.kdbx> <out.kdbx> <password>");
        std::process::exit(2);
    };
    let data = std::fs::read(input).expect("read input");
    let credentials =
        VaultCredentials::from_password(SecretBytes::from(password.as_bytes().to_vec()));
    let mut session =
        VaultSession::new(open_vault(&data, &credentials).expect("open")).expect("session");
    let root = session.info().root_group;

    let group = session.create_group(root, "Interop Group").expect("group");
    session
        .create_entry(
            group,
            &EntryDraft {
                title: "Interop Entry".to_owned(),
                username: "interop-user".to_owned(),
                password: SecretString::new("interop-pässword <&>".to_owned()),
                url: "https://interop.example.com".to_owned(),
                notes: "written by Vazie Keep".to_owned(),
                custom_fields: vec![CustomFieldDraft {
                    name: "Interop Secret".to_owned(),
                    value: SecretString::new("s3cr3t".to_owned()),
                    protected: true,
                }],
                tags: vec!["interop".to_owned()],
                favorite: true,
                expires: Some(1_791_244_800),
            },
        )
        .expect("entry");
    let interop = session
        .entries()
        .find(|e| e.title.as_plain() == Some("Interop Entry"))
        .expect("interop")
        .id;
    let plus = Access {
        state: AccessState::PlusActive,
        plus_features: true,
        needs_online_verification: false,
        lease_valid_until: None,
        observed_device_time: 0,
    };
    session
        .add_attachment(interop, "interop.txt", b"attached by Vazie Keep", &plus)
        .expect("attachment");
    let mail = session
        .entries()
        .find(|e| e.title.as_plain() == Some("Example Mail"))
        .expect("mail")
        .id;
    session
        .update_entry(
            mail,
            &EntryDraft {
                title: "Example Mail".to_owned(),
                username: "alice-edited@example.org".to_owned(),
                password: SecretString::new("hunter2-edited".to_owned()),
                url: "https://mail.example.org/login".to_owned(),
                notes: String::new(),
                custom_fields: vec![CustomFieldDraft {
                    name: "Recovery Code".to_owned(),
                    value: SecretString::new("RC-0000-1111".to_owned()),
                    protected: true,
                }],
                tags: vec!["mail".to_owned()],
                favorite: false,
                expires: None,
            },
        )
        .expect("update");
    let snapshot = session.prepare_save().expect("save");
    std::fs::write(output, &snapshot.bytes).expect("write output");
}
