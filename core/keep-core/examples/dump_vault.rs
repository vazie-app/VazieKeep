//! Interop helper for synthetic test vaults only: prints groups and entries, and the password
//! of one entry when asked.
//!
//!     cargo run -p keep-core --example dump_vault -- <vault.kdbx> <password> [title]

use keep_core::{SecretBytes, SecretField, VaultCredentials, VaultSession, open_vault};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (path, password) = (&args[1], &args[2]);
    let credentials =
        VaultCredentials::from_password(SecretBytes::from(password.as_bytes().to_vec()));
    let vault = open_vault(&std::fs::read(path).expect("read"), &credentials).expect("open");
    let session = VaultSession::new(vault).expect("session");
    let groups = session.groups();
    let name = |id| {
        groups
            .iter()
            .find(|g| g.id == id)
            .map_or("?", |g| g.name.as_str())
    };
    for entry in session.entries() {
        println!(
            "entry {} | {} | group {} | attachments {:?}",
            entry.title.as_plain().unwrap_or("<protected>"),
            entry.username.as_plain().unwrap_or("<protected>"),
            name(entry.group),
            entry.attachment_names
        );
        if args.get(3).map(String::as_str) == entry.title.as_plain() {
            let secret = session
                .reveal(entry.id, SecretField::Password)
                .expect("reveal");
            println!("password {}", secret.expose());
        }
    }
}
