//! Verifies one lease token against a key list, the way the apps do.
//!
//!     cargo run -p keep-core --example verify_lease -- "kid:publicKey,kid2:publicKey2" "<token>"
//!
//! Prints `accepted: <tier> <key id>` and exits 0, or `rejected: <reason>` and exits 1.

use keep_core::{Tier, TrustedKey, verify_lease};

fn main() {
    let mut args = std::env::args().skip(1);
    let (Some(keys), Some(token)) = (args.next(), args.next()) else {
        eprintln!("usage: verify_lease <kid:publicKey,...> <token>");
        std::process::exit(2);
    };
    let keys: Vec<TrustedKey> = keys
        .split(',')
        .filter_map(|entry| entry.trim().split_once(':'))
        .filter_map(|(kid, key)| TrustedKey::from_base64url(kid, key).ok())
        .collect();
    match verify_lease(&token, &keys) {
        Ok(lease) => {
            let tier = if lease.tier == Tier::KeepPlus {
                "keep_plus"
            } else {
                "free"
            };
            println!("accepted: {tier} {}", lease.key_id);
        }
        Err(error) => {
            println!("rejected: {error:?}");
            std::process::exit(1);
        }
    }
}
