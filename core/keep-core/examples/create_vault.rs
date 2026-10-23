//! Writes a new vault for compatibility checks: `create_vault <out.kdbx> <name> <preset> [key-file-out]`.
//! The password is read from the `VAZIE_PASSWORD` environment variable. Used by
//! `tooling/test-vectors/check_created_vault.py`; never used by the apps.

use keep_core::{
    CreateVaultConfig, SecretBytes, SecurityPreset, VaultCredentials, create_vault,
    generate_key_file,
};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (out, name, preset) = (&args[1], &args[2], args[3].as_str());
    let preset = if preset == "stronger" {
        SecurityPreset::Stronger
    } else {
        SecurityPreset::Balanced
    };
    let password = std::env::var("VAZIE_PASSWORD").expect("VAZIE_PASSWORD");
    let key_file = args.get(4).map(|path| {
        let key = generate_key_file().expect("key file");
        std::fs::write(path, key.expose()).expect("write key file");
        SecretBytes::new(key.expose().to_vec())
    });
    let credentials =
        VaultCredentials::new(Some(SecretBytes::new(password.into_bytes())), key_file)
            .expect("credentials");
    let created =
        create_vault(&CreateVaultConfig::new(name.clone(), preset), &credentials).expect("create");
    std::fs::write(out, &created.bytes).expect("write vault");
}
