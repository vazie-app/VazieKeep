#![no_main]

use keep_core::{OpenLimits, SecretBytes, VaultCredentials, open_vault_with_limits};
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let credentials = VaultCredentials::from_password(SecretBytes::from(b"fuzz".to_vec()));
    let limits = OpenLimits {
        max_file_size: 1 << 20,
        max_kdf_memory_bytes: 64 * 1024,
        max_kdf_iterations: 2,
        max_kdf_parallelism: 2,
        max_aes_kdf_rounds: 16,
        ..OpenLimits::default()
    };
    let _ = open_vault_with_limits(data, &credentials, &limits);
});
