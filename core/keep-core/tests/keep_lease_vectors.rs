//! The shared Keep+ lease vectors: signed by `tooling/test-vectors/generate_keep_lease.py` with
//! OpenSSL, verified here, and by the Android and Apple builds against the same file.

use keep_core::{LeaseError, Tier, TrustedKey, verify_lease};
use serde_json::Value;

fn vectors() -> Value {
    let path = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../shared/test-vectors/keep-lease/vectors.json"
    );
    serde_json::from_str(&std::fs::read_to_string(path).expect("vectors file")).expect("valid json")
}

fn keys(v: &Value) -> Vec<TrustedKey> {
    v["keys"]
        .as_array()
        .unwrap()
        .iter()
        .map(|k| {
            TrustedKey::from_base64url(
                k["kid"].as_str().unwrap(),
                k["public_key"].as_str().unwrap(),
            )
            .unwrap()
        })
        .collect()
}

#[test]
fn every_vector_is_accepted_or_rejected_as_declared() {
    let v = vectors();
    let keys = keys(&v);
    for case in v["cases"].as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        let result = verify_lease(case["token"].as_str().unwrap(), &keys);
        if case["valid"].as_bool().unwrap() {
            let lease = result.unwrap_or_else(|e| panic!("{name} rejected: {e:?}"));
            let tier = if case["tier"] == "free" {
                Tier::Free
            } else {
                Tier::KeepPlus
            };
            assert_eq!(lease.tier, tier, "{name}");
            assert_eq!(
                lease.lifetime,
                case["lifetime"].as_bool().unwrap(),
                "{name}"
            );
            assert_eq!(lease.subject, v["subject"].as_str().unwrap(), "{name}");
            assert_eq!(lease.issued_at, v["issued_at"].as_i64().unwrap(), "{name}");
        } else {
            assert!(result.is_err(), "{name} must be rejected");
        }
    }
}

#[test]
fn tampering_is_a_signature_failure_and_the_wrong_key_is_not_trusted() {
    let v = vectors();
    let keys = keys(&v);
    let by = |name: &str| {
        v["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|c| c["name"] == name)
            .unwrap()["token"]
            .as_str()
            .unwrap()
            .to_owned()
    };
    assert_eq!(
        verify_lease(&by("tier_tampered"), &keys),
        Err(LeaseError::BadSignature)
    );
    assert_eq!(
        verify_lease(&by("signed_by_another_key"), &keys),
        Err(LeaseError::BadSignature)
    );
    assert_eq!(
        verify_lease(&by("unknown_kid"), &keys),
        Err(LeaseError::UnknownKey)
    );
    assert_eq!(
        verify_lease(&by("alg_none"), &keys),
        Err(LeaseError::UnsupportedAlgorithm)
    );
    assert_eq!(
        verify_lease(&by("alg_hs256"), &keys),
        Err(LeaseError::UnsupportedAlgorithm)
    );
    assert_eq!(
        verify_lease(&by("plus_90_days"), &[]),
        Err(LeaseError::UnknownKey)
    );
}

#[test]
fn a_correctly_signed_but_inconsistent_lease_is_refused() {
    let v = vectors();
    let keys = keys(&v);
    let by = |name: &str| {
        v["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|c| c["name"] == name)
            .unwrap()["token"]
            .as_str()
            .unwrap()
            .to_owned()
    };
    assert_eq!(
        verify_lease(&by("wrong_issuer"), &keys),
        Err(LeaseError::Unsupported)
    );
    assert_eq!(
        verify_lease(&by("wrong_product"), &keys),
        Err(LeaseError::Unsupported)
    );
    assert_eq!(
        verify_lease(&by("future_version"), &keys),
        Err(LeaseError::Unsupported)
    );
    assert_eq!(
        verify_lease(&by("lease_outlives_entitlement"), &keys),
        Err(LeaseError::Inconsistent)
    );
    assert_eq!(
        verify_lease(&by("lifetime_with_end"), &keys),
        Err(LeaseError::Inconsistent)
    );
}

#[test]
fn oversized_or_empty_tokens_never_reach_the_parser() {
    assert_eq!(verify_lease("", &[]), Err(LeaseError::Malformed));
    assert_eq!(
        verify_lease(&"a.".repeat(5000), &[]),
        Err(LeaseError::Malformed)
    );
}
