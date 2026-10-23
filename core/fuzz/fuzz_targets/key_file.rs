#![no_main]

use keep_core::{SecretBytes, VaultCredentials, detect_key_file_format};
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let format = detect_key_file_format(data);
    let credentials = VaultCredentials::new(None, Some(SecretBytes::from(data.to_vec())));
    assert_eq!(format.is_ok(), credentials.is_ok());
});
