use std::fmt;

use hmac::{Hmac, KeyInit, Mac};
use sha1::Sha1;
use sha2::{Sha256, Sha512};
use zeroize::Zeroizing;

use crate::model::EntryField;
use crate::secret::{SecretBytes, SecretString};

pub const OTP_FIELD: &str = "otp";
pub const MAX_SECRET_BYTES: usize = 128;
pub const MIN_PERIOD: u32 = 1;
pub const MAX_PERIOD: u32 = 3600;
pub const MAX_INPUT_CHARS: usize = 2048;
const STEAM_ALPHABET: &[u8; 26] = b"23456789BCDFGHJKMNPQRTVWXY";
const STEAM_LENGTH: usize = 5;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TotpAlgorithm {
    Sha1,
    Sha256,
    Sha512,
}

impl TotpAlgorithm {
    pub const fn uri_name(self) -> &'static str {
        match self {
            Self::Sha1 => "SHA1",
            Self::Sha256 => "SHA256",
            Self::Sha512 => "SHA512",
        }
    }

    fn parse(text: &str) -> Option<Self> {
        match text
            .trim()
            .to_ascii_uppercase()
            .replace(['-', '_'], "")
            .as_str()
        {
            "SHA1" | "HMACSHA1" => Some(Self::Sha1),
            "SHA256" | "HMACSHA256" => Some(Self::Sha256),
            "SHA512" | "HMACSHA512" => Some(Self::Sha512),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TotpEncoder {
    Decimal,
    Steam,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum TotpError {
    Empty,
    TooLong,
    UnsupportedType,
    MissingSecret,
    InvalidSecret,
    InvalidAlgorithm,
    InvalidDigits,
    InvalidPeriod,
    Migration,
    Malformed,
}

impl fmt::Display for TotpError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Empty => "nothing to read",
            Self::TooLong => "input too long",
            Self::UnsupportedType => "only time-based codes are supported",
            Self::MissingSecret => "no secret",
            Self::InvalidSecret => "the secret is not valid",
            Self::InvalidAlgorithm => "unsupported algorithm",
            Self::InvalidDigits => "digits must be 6 to 8",
            Self::InvalidPeriod => "period out of range",
            Self::Migration => "an export from another authenticator app",
            Self::Malformed => "malformed one-time code setup",
        })
    }
}

impl std::error::Error for TotpError {}

pub struct TotpConfig {
    secret: SecretBytes,
    algorithm: TotpAlgorithm,
    digits: u8,
    period: u32,
    encoder: TotpEncoder,
    issuer: String,
    account: String,
}

impl fmt::Debug for TotpConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("TotpConfig")
            .field("algorithm", &self.algorithm)
            .field("digits", &self.digits)
            .field("period", &self.period)
            .field("encoder", &self.encoder)
            .finish_non_exhaustive()
    }
}

pub struct TotpCode {
    pub code: SecretString,
    pub period: u32,
    pub seconds_remaining: u32,
}

impl fmt::Debug for TotpCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("TotpCode")
            .field("period", &self.period)
            .field("seconds_remaining", &self.seconds_remaining)
            .finish_non_exhaustive()
    }
}

struct Params {
    algorithm: TotpAlgorithm,
    digits: u8,
    period: u32,
    encoder: TotpEncoder,
    issuer: String,
    account: String,
}

impl Default for Params {
    fn default() -> Self {
        Self {
            algorithm: TotpAlgorithm::Sha1,
            digits: 6,
            period: 30,
            encoder: TotpEncoder::Decimal,
            issuer: String::new(),
            account: String::new(),
        }
    }
}

impl TotpConfig {
    pub fn parse(input: &str) -> Result<Self, TotpError> {
        let text = input.trim();
        if text.is_empty() {
            return Err(TotpError::Empty);
        }
        if text.chars().count() > MAX_INPUT_CHARS {
            return Err(TotpError::TooLong);
        }
        if starts_with_ignore_case(text, "otpauth-migration://") {
            return Err(TotpError::Migration);
        }
        if starts_with_ignore_case(text, "otpauth://") {
            return Self::from_uri(text);
        }
        if text.contains("key=") && text.contains('&') {
            return Self::from_key_query(text);
        }
        Self::build(
            base32_decode(text).ok_or(TotpError::InvalidSecret)?,
            Params::default(),
        )
    }

    pub fn from_fields(fields: &[EntryField]) -> Option<Result<Self, TotpError>> {
        let find = |name: &str| {
            fields
                .iter()
                .find(|f| f.name == name)
                .map(|f| f.value.expose())
                .filter(|v| !v.trim().is_empty())
        };
        if let Some(value) = find(OTP_FIELD) {
            return Some(Self::parse(value));
        }
        if let Some(seed) = find("TimeOtp-Secret-Base32") {
            return Some(Self::key_pass_2(&find, base32_decode(seed)));
        }
        if let Some(seed) = find("TimeOtp-Secret-Hex") {
            return Some(Self::key_pass_2(&find, hex_decode(seed)));
        }
        if let Some(seed) = find("TimeOtp-Secret") {
            return Some(Self::key_pass_2(&find, Some(seed.as_bytes().to_vec())));
        }
        if let Some(seed) = find("TOTP Seed") {
            return Some(Self::key_pass_xc_legacy(seed, find("TOTP Settings")));
        }
        None
    }

    pub fn code_at(&self, unix_seconds: u64) -> TotpCode {
        let period = u64::from(self.period);
        let counter = (unix_seconds / period).to_be_bytes();
        let mac = self.hmac(&counter);
        let offset = usize::from(mac[mac.len() - 1] & 0x0f);
        let mut binary = u32::from_be_bytes([
            mac[offset],
            mac[offset + 1],
            mac[offset + 2],
            mac[offset + 3],
        ]) & 0x7fff_ffff;
        let code = match self.encoder {
            TotpEncoder::Decimal => {
                let modulus = 10u32.pow(u32::from(self.digits));
                format!(
                    "{:0width$}",
                    binary % modulus,
                    width = usize::from(self.digits)
                )
            }
            TotpEncoder::Steam => {
                let mut out = String::with_capacity(STEAM_LENGTH);
                for _ in 0..STEAM_LENGTH {
                    out.push(char::from(STEAM_ALPHABET[(binary % 26) as usize]));
                    binary /= 26;
                }
                out
            }
        };
        let elapsed = u32::try_from(unix_seconds % period).unwrap_or(0);
        TotpCode {
            code: SecretString::new(code),
            period: self.period,
            seconds_remaining: self.period - elapsed,
        }
    }

    pub fn to_uri(&self) -> SecretString {
        let label = if self.issuer.is_empty() {
            percent_encode(&self.account)
        } else {
            format!(
                "{}:{}",
                percent_encode(&self.issuer),
                percent_encode(&self.account)
            )
        };
        let mut uri = format!(
            "otpauth://totp/{label}?secret={}&algorithm={}&digits={}&period={}",
            base32_encode(self.secret.expose()),
            self.algorithm.uri_name(),
            self.digits,
            self.period,
        );
        if !self.issuer.is_empty() {
            uri.push_str("&issuer=");
            uri.push_str(&percent_encode(&self.issuer));
        }
        if self.encoder == TotpEncoder::Steam {
            uri.push_str("&encoder=steam");
        }
        SecretString::new(uri)
    }

    pub fn algorithm(&self) -> TotpAlgorithm {
        self.algorithm
    }

    pub fn digits(&self) -> u8 {
        match self.encoder {
            TotpEncoder::Decimal => self.digits,
            TotpEncoder::Steam => 5,
        }
    }

    pub fn period(&self) -> u32 {
        self.period
    }

    pub fn encoder(&self) -> TotpEncoder {
        self.encoder
    }

    pub fn issuer(&self) -> &str {
        &self.issuer
    }

    pub fn account(&self) -> &str {
        &self.account
    }

    fn hmac(&self, message: &[u8]) -> Zeroizing<Vec<u8>> {
        let key = self.secret.expose();
        macro_rules! mac {
            ($hash:ty) => {{
                let mut mac = <Hmac<$hash> as KeyInit>::new_from_slice(key)
                    .expect("HMAC accepts a key of any length");
                mac.update(message);
                mac.finalize().into_bytes().to_vec()
            }};
        }
        Zeroizing::new(match self.algorithm {
            TotpAlgorithm::Sha1 => mac!(Sha1),
            TotpAlgorithm::Sha256 => mac!(Sha256),
            TotpAlgorithm::Sha512 => mac!(Sha512),
        })
    }

    fn build(secret: Vec<u8>, params: Params) -> Result<Self, TotpError> {
        let secret = SecretBytes::new(secret);
        if secret.expose().is_empty() {
            return Err(TotpError::MissingSecret);
        }
        if secret.expose().len() > MAX_SECRET_BYTES {
            return Err(TotpError::InvalidSecret);
        }
        if params.encoder == TotpEncoder::Decimal && !(6..=8).contains(&params.digits) {
            return Err(TotpError::InvalidDigits);
        }
        if !(MIN_PERIOD..=MAX_PERIOD).contains(&params.period) {
            return Err(TotpError::InvalidPeriod);
        }
        Ok(Self {
            secret,
            algorithm: params.algorithm,
            digits: params.digits,
            period: params.period,
            encoder: params.encoder,
            issuer: params.issuer,
            account: params.account,
        })
    }

    fn from_uri(text: &str) -> Result<Self, TotpError> {
        let rest = &text["otpauth://".len()..];
        let (kind, rest) = rest.split_once('/').ok_or(TotpError::Malformed)?;
        if kind.eq_ignore_ascii_case("hotp") {
            return Err(TotpError::UnsupportedType);
        }
        if !kind.eq_ignore_ascii_case("totp") {
            return Err(TotpError::Malformed);
        }
        let (label, query) = rest.split_once('?').unwrap_or((rest, ""));
        let query = parse_query(query)?;
        let get = |key: &str| lookup(&query, key);

        let secret = get("secret").ok_or(TotpError::MissingSecret)?;
        let mut params = Params {
            algorithm: match get("algorithm") {
                Some(name) => TotpAlgorithm::parse(name).ok_or(TotpError::InvalidAlgorithm)?,
                None => TotpAlgorithm::Sha1,
            },
            digits: number(get("digits"), 6, TotpError::InvalidDigits)?,
            period: number(get("period"), 30, TotpError::InvalidPeriod)?,
            encoder: if get("encoder").is_some_and(|e| e.eq_ignore_ascii_case("steam")) {
                TotpEncoder::Steam
            } else {
                TotpEncoder::Decimal
            },
            ..Params::default()
        };
        let decoded = percent_decode(label.trim_start_matches('/'))?;
        let (label_issuer, account) = match decoded.split_once(':') {
            Some((issuer, account)) => (issuer.trim().to_owned(), account.trim().to_owned()),
            None => (String::new(), decoded.trim().to_owned()),
        };
        params.issuer = get("issuer").map_or(label_issuer, |v| v.trim().to_owned());
        params.account = account;
        Self::build(
            base32_decode(secret).ok_or(TotpError::InvalidSecret)?,
            params,
        )
    }

    fn from_key_query(text: &str) -> Result<Self, TotpError> {
        let query = parse_query(text)?;
        let get = |key: &str| lookup(&query, key);
        let secret = get("key").ok_or(TotpError::MissingSecret)?;
        let params = Params {
            algorithm: match get("otpHashMode") {
                Some(name) => TotpAlgorithm::parse(name).ok_or(TotpError::InvalidAlgorithm)?,
                None => TotpAlgorithm::Sha1,
            },
            digits: number(get("size"), 6, TotpError::InvalidDigits)?,
            period: number(get("step"), 30, TotpError::InvalidPeriod)?,
            ..Params::default()
        };
        Self::build(
            base32_decode(secret).ok_or(TotpError::InvalidSecret)?,
            params,
        )
    }

    fn key_pass_2<'a>(
        find: &impl Fn(&str) -> Option<&'a str>,
        secret: Option<Vec<u8>>,
    ) -> Result<Self, TotpError> {
        let params = Params {
            algorithm: match find("TimeOtp-Algorithm") {
                Some(name) => TotpAlgorithm::parse(name).ok_or(TotpError::InvalidAlgorithm)?,
                None => TotpAlgorithm::Sha1,
            },
            digits: number(find("TimeOtp-Length"), 6, TotpError::InvalidDigits)?,
            period: number(find("TimeOtp-Period"), 30, TotpError::InvalidPeriod)?,
            ..Params::default()
        };
        Self::build(secret.ok_or(TotpError::InvalidSecret)?, params)
    }

    fn key_pass_xc_legacy(seed: &str, settings: Option<&str>) -> Result<Self, TotpError> {
        let mut params = Params::default();
        if let Some(text) = settings {
            let (period, tail) = text.trim().split_once(';').ok_or(TotpError::Malformed)?;
            params.period = number(Some(period), 30, TotpError::InvalidPeriod)?;
            if tail.trim().eq_ignore_ascii_case("s") {
                params.encoder = TotpEncoder::Steam;
                params.digits = 5;
            } else {
                params.digits = number(Some(tail), 6, TotpError::InvalidDigits)?;
            }
        }
        Self::build(base32_decode(seed).ok_or(TotpError::InvalidSecret)?, params)
    }
}

fn number<T: std::str::FromStr>(
    text: Option<&str>,
    default: T,
    error: TotpError,
) -> Result<T, TotpError> {
    match text {
        Some(text) => text.trim().parse::<T>().map_err(|_| error),
        None => Ok(default),
    }
}

fn lookup<'a>(query: &'a [(String, String)], key: &str) -> Option<&'a str> {
    query
        .iter()
        .find(|(k, _)| k.eq_ignore_ascii_case(key))
        .map(|(_, v)| v.as_str())
}

fn starts_with_ignore_case(text: &str, prefix: &str) -> bool {
    text.len() >= prefix.len()
        && text.as_bytes()[..prefix.len()].eq_ignore_ascii_case(prefix.as_bytes())
}

fn parse_query(query: &str) -> Result<Vec<(String, String)>, TotpError> {
    query
        .split('&')
        .filter(|pair| !pair.is_empty())
        .map(|pair| {
            let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
            Ok((percent_decode(key)?, percent_decode(value)?))
        })
        .collect()
}

fn percent_decode(text: &str) -> Result<String, TotpError> {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hi = bytes
                .get(i + 1)
                .and_then(|b| hex_digit(*b))
                .ok_or(TotpError::Malformed)?;
            let lo = bytes
                .get(i + 2)
                .and_then(|b| hex_digit(*b))
                .ok_or(TotpError::Malformed)?;
            out.push(hi << 4 | lo);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).map_err(|_| TotpError::Malformed)
}

fn percent_encode(text: &str) -> String {
    use std::fmt::Write as _;
    let mut out = String::with_capacity(text.len());
    for byte in text.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b'~' | b'@') {
            out.push(char::from(byte));
        } else {
            let _ = write!(out, "%{byte:02X}");
        }
    }
    out
}

fn hex_digit(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

fn hex_decode(text: &str) -> Option<Vec<u8>> {
    let digits: Vec<u8> = text.bytes().filter(|b| !b.is_ascii_whitespace()).collect();
    if digits.is_empty() || digits.len() % 2 != 0 {
        return None;
    }
    digits
        .chunks(2)
        .map(|pair| Some(hex_digit(pair[0])? << 4 | hex_digit(pair[1])?))
        .collect()
}

fn base32_decode(text: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(text.len() * 5 / 8);
    let mut buffer: u32 = 0;
    let mut bits = 0u32;
    for ch in text.chars() {
        if ch.is_whitespace() || ch == '-' || ch == '=' {
            continue;
        }
        let value = match ch.to_ascii_uppercase() {
            c @ 'A'..='Z' => c as u32 - 'A' as u32,
            c @ '2'..='7' => c as u32 - '2' as u32 + 26,
            _ => return None,
        };
        buffer = buffer << 5 | value;
        bits += 5;
        if bits >= 8 {
            bits -= 8;
            out.push(u8::try_from(buffer >> bits).ok()?);
            buffer &= (1 << bits) - 1;
        }
    }
    (!out.is_empty()).then_some(out)
}

fn base32_encode(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 32] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    let mut out = String::with_capacity(bytes.len() * 8 / 5 + 1);
    let mut buffer: u32 = 0;
    let mut bits = 0u32;
    for &byte in bytes {
        buffer = buffer << 8 | u32::from(byte);
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            out.push(char::from(ALPHABET[((buffer >> bits) & 0x1f) as usize]));
        }
        buffer &= (1 << bits) - 1;
    }
    if bits > 0 {
        out.push(char::from(
            ALPHABET[((buffer << (5 - bits)) & 0x1f) as usize],
        ));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::FieldValue;

    const SHA1_KEY: &[u8] = b"12345678901234567890";
    const SHA256_KEY: &[u8] = b"12345678901234567890123456789012";
    const SHA512_KEY: &[u8] = b"1234567890123456789012345678901234567890123456789012345678901234";

    fn rfc(key: &[u8], algorithm: TotpAlgorithm) -> TotpConfig {
        TotpConfig::build(
            key.to_vec(),
            Params {
                algorithm,
                digits: 8,
                period: 30,
                ..Params::default()
            },
        )
        .unwrap()
    }

    fn code(config: &TotpConfig, at: u64) -> String {
        config.code_at(at).code.expose().to_owned()
    }

    fn field(name: &str, value: &str) -> EntryField {
        EntryField {
            name: name.to_owned(),
            value: FieldValue::Protected(SecretString::new(value.to_owned())),
        }
    }

    #[test]
    fn rfc_6238_vectors() {
        let cases: [(&[u8], TotpAlgorithm, [&str; 6]); 3] = [
            (
                SHA1_KEY,
                TotpAlgorithm::Sha1,
                [
                    "94287082", "07081804", "14050471", "89005924", "69279037", "65353130",
                ],
            ),
            (
                SHA256_KEY,
                TotpAlgorithm::Sha256,
                [
                    "46119246", "68084774", "67062674", "91819424", "90698825", "77737706",
                ],
            ),
            (
                SHA512_KEY,
                TotpAlgorithm::Sha512,
                [
                    "90693936", "25091201", "99943326", "93441116", "38618901", "47863826",
                ],
            ),
        ];
        let times = [
            59,
            1_111_111_109,
            1_111_111_111,
            1_234_567_890,
            2_000_000_000,
            20_000_000_000,
        ];
        for (key, algorithm, expected) in cases {
            let config = rfc(key, algorithm);
            for (time, want) in times.iter().zip(expected) {
                assert_eq!(code(&config, *time), want, "{algorithm:?} at {time}");
            }
        }
    }

    #[test]
    fn six_digits_keep_leading_zeros() {
        let config = TotpConfig::parse("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ").unwrap();
        assert_eq!(code(&config, 59), "287082");
        assert_eq!(code(&config, 1_111_111_109), "081804");
    }

    #[test]
    fn remaining_time_counts_down_to_the_next_step() {
        let config = TotpConfig::parse("GEZDGNBVGY3TQOJQ").unwrap();
        assert_eq!(config.code_at(60).seconds_remaining, 30);
        assert_eq!(config.code_at(89).seconds_remaining, 1);
        assert_eq!(config.code_at(90).seconds_remaining, 30);
        assert_eq!(config.code_at(61).period, 30);
    }

    #[test]
    fn uri_carries_every_setting() {
        let config = TotpConfig::parse(
            "otpauth://totp/Example%20Co:anna%40example.com?secret=GEZDGNBVGY3TQOJQ&issuer=Example%20Co&algorithm=SHA256&digits=8&period=60",
        )
        .unwrap();
        assert_eq!(config.algorithm(), TotpAlgorithm::Sha256);
        assert_eq!(config.digits(), 8);
        assert_eq!(config.period(), 60);
        assert_eq!(config.issuer(), "Example Co");
        assert_eq!(config.account(), "anna@example.com");
    }

    #[test]
    fn a_uri_survives_a_round_trip() {
        let original = TotpConfig::parse(
            "otpauth://totp/Mail:me@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Mail&digits=7&period=45&algorithm=SHA512",
        )
        .unwrap();
        let again = TotpConfig::parse(original.to_uri().expose()).unwrap();
        for at in [0, 59, 1_700_000_000] {
            assert_eq!(code(&original, at), code(&again, at));
        }
        assert_eq!(again.issuer(), "Mail");
        assert_eq!(again.account(), "me@example.com");
        assert_eq!(again.digits(), 7);
        assert_eq!(again.period(), 45);
    }

    #[test]
    fn a_bare_secret_is_forgiving_about_spaces_case_and_padding() {
        let spaced = TotpConfig::parse("gezd gnbv gy3t qojq gezd gnbv gy3t qojq").unwrap();
        let plain = TotpConfig::parse("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ====").unwrap();
        assert_eq!(code(&spaced, 59), code(&plain, 59));
    }

    #[test]
    fn steam_codes_use_their_own_alphabet() {
        let config = TotpConfig::parse(
            "otpauth://totp/Steam:me?secret=JBSWY3DPEHPK3PXP&encoder=steam&issuer=Steam",
        )
        .unwrap();
        let value = code(&config, 1_700_000_000);
        assert_eq!(value.len(), 5);
        assert!(value.bytes().all(|b| STEAM_ALPHABET.contains(&b)));
        assert_eq!(config.digits(), 5);
        assert!(config.to_uri().expose().contains("encoder=steam"));
    }

    #[test]
    fn what_cannot_be_a_code_is_refused() {
        let cases = [
            ("", TotpError::Empty),
            ("   ", TotpError::Empty),
            (
                "otpauth://hotp/x?secret=GEZDGNBV&counter=1",
                TotpError::UnsupportedType,
            ),
            ("otpauth://totp/x", TotpError::MissingSecret),
            ("otpauth://totp/x?secret=!!!!", TotpError::InvalidSecret),
            (
                "otpauth://totp/x?secret=GEZDGNBV&algorithm=MD5",
                TotpError::InvalidAlgorithm,
            ),
            (
                "otpauth://totp/x?secret=GEZDGNBV&digits=4",
                TotpError::InvalidDigits,
            ),
            (
                "otpauth://totp/x?secret=GEZDGNBV&digits=nine",
                TotpError::InvalidDigits,
            ),
            (
                "otpauth://totp/x?secret=GEZDGNBV&period=0",
                TotpError::InvalidPeriod,
            ),
            (
                "otpauth://totp/x?secret=GEZDGNBV&period=99999",
                TotpError::InvalidPeriod,
            ),
            ("otpauth://totp/x%ZZ?secret=GEZDGNBV", TotpError::Malformed),
            ("not a secret 1 8 9", TotpError::InvalidSecret),
            ("otpauth-migration://offline?data=Cg", TotpError::Migration),
        ];
        for (input, error) in cases {
            assert_eq!(TotpConfig::parse(input).unwrap_err(), error, "{input}");
        }
        assert_eq!(
            TotpConfig::parse(&"A".repeat(MAX_INPUT_CHARS + 1)).unwrap_err(),
            TotpError::TooLong
        );
    }

    #[test]
    fn an_entry_without_a_code_setup_has_none() {
        assert!(TotpConfig::from_fields(&[field("Recovery", "abc")]).is_none());
        assert!(TotpConfig::from_fields(&[field("otp", "  ")]).is_none());
    }

    #[test]
    fn keepassxc_and_keepass_2_field_layouts_are_read() {
        let expected = code(
            &TotpConfig::parse("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ").unwrap(),
            59,
        )[..]
            .to_owned();

        let xc = TotpConfig::from_fields(&[field(
            "otp",
            "otpauth://totp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
        )])
        .unwrap()
        .unwrap();
        assert_eq!(code(&xc, 59), expected);

        let keeotp = TotpConfig::from_fields(&[field(
            "otp",
            "key=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&size=6&step=30",
        )])
        .unwrap()
        .unwrap();
        assert_eq!(code(&keeotp, 59), expected);

        let legacy = TotpConfig::from_fields(&[
            field("TOTP Seed", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"),
            field("TOTP Settings", "30;6"),
        ])
        .unwrap()
        .unwrap();
        assert_eq!(code(&legacy, 59), expected);

        let plugin = TotpConfig::from_fields(&[
            field("TimeOtp-Secret-Base32", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"),
            field("TimeOtp-Length", "8"),
            field("TimeOtp-Algorithm", "HMAC-SHA-1"),
        ])
        .unwrap()
        .unwrap();
        assert_eq!(code(&plugin, 59), "94287082");

        let hex = TotpConfig::from_fields(&[
            field(
                "TimeOtp-Secret-Hex",
                "3132333435363738393031323334353637383930",
            ),
            field("TimeOtp-Length", "8"),
        ])
        .unwrap()
        .unwrap();
        assert_eq!(code(&hex, 59), "94287082");

        let utf8 = TotpConfig::from_fields(&[
            field("TimeOtp-Secret", "12345678901234567890"),
            field("TimeOtp-Length", "8"),
        ])
        .unwrap()
        .unwrap();
        assert_eq!(code(&utf8, 59), "94287082");
    }

    #[test]
    fn a_broken_field_is_an_error_not_silence() {
        let result =
            TotpConfig::from_fields(&[field("otp", "otpauth://totp/x?secret=!!")]).unwrap();
        assert_eq!(result.unwrap_err(), TotpError::InvalidSecret);
    }

    #[test]
    fn debug_output_never_shows_the_secret_or_the_code() {
        let config = TotpConfig::parse("JBSWY3DPEHPK3PXP").unwrap();
        let shown = format!("{config:?} {:?}", config.code_at(1_700_000_000));
        assert!(!shown.contains("JBSWY3DPEHPK3PXP"));
        assert!(!shown.contains(&code(&config, 1_700_000_000)));
    }

    #[test]
    fn base32_round_trips() {
        for bytes in [
            &b"f"[..],
            b"fo",
            b"foo",
            b"foob",
            b"fooba",
            b"foobar",
            b"\x00\xff\x10",
        ] {
            assert_eq!(base32_decode(&base32_encode(bytes)).unwrap(), bytes);
        }
        assert_eq!(base32_encode(b"foobar"), "MZXW6YTBOI");
    }
}
