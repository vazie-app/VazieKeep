#![no_main]

use keep_core::{ItemId, SecretField, SessionId, VaultSessionStore};
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let Ok(text) = std::str::from_utf8(data) else {
        return;
    };
    let store = VaultSessionStore::new(1);
    if let Ok(id) = SessionId::parse(text) {
        assert_eq!(SessionId::parse(&id.to_string()), Ok(id));
        assert!(store.get(id).is_err());
        assert!(!store.close(id));
    }
    if let Ok(id) = text.parse::<ItemId>() {
        assert_eq!(id.to_string().parse::<ItemId>(), Ok(id));
    }
    let _ = SecretField::from_kdbx_key(text);
});
