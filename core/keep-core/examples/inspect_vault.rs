//! Prints the name, entry count and entry titles of a vault, for compatibility checks:
//! `inspect_vault <file.kdbx> [key-file]` with the password in `VAZIE_PASSWORD`.

use keep_core::{SecretBytes, VaultCredentials, VaultSession, open_vault};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let data = std::fs::read(&args[1]).expect("read vault");
    let password = std::env::var("VAZIE_PASSWORD")
        .ok()
        .map(|p| SecretBytes::new(p.into_bytes()));
    let key_file = args
        .get(2)
        .map(|path| SecretBytes::new(std::fs::read(path).expect("key file")));
    let credentials = VaultCredentials::new(password, key_file).expect("credentials");
    let vault = open_vault(&data, &credentials).expect("open");
    let session = VaultSession::new(vault).expect("session");
    let info = session.info();
    println!(
        "name={} entries={} groups={} format={}.{}",
        info.name, info.entry_count, info.group_count, info.format.major, info.format.minor
    );
    for entry in session.entries() {
        println!("entry {}", entry.title.as_plain().unwrap_or("<protected>"));
    }
}
