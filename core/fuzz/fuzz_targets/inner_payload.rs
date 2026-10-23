#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| keep_core::fuzzing::parse_inner_payload(data));
