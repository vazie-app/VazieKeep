use super::reader::ByteReader;
use super::variant::VariantDictionary;
use crate::error::{Limit, OpenError};
use crate::limits::OpenLimits;
use crate::model::{Cipher, KdbxVersion, Kdf};

const ERR: OpenError = OpenError::InvalidFormat;

pub(super) const CIPHER_AES256: [u8; 16] = uuid(0x31c1_f2e6_bf71_4350_be58_0521_6afc_5aff);
pub(super) const CIPHER_CHACHA20: [u8; 16] = uuid(0xd603_8a2b_8b6f_4cb5_a524_339a_31db_b59a);
pub(super) const KDF_AES: [u8; 16] = uuid(0xc9d9_f39a_628a_4460_bf74_0d08_c18a_4fea);
pub(super) const KDF_ARGON2D: [u8; 16] = uuid(0xef63_6ddf_8c29_444b_91f7_a9a4_03e3_0a0c);
pub(super) const KDF_ARGON2ID: [u8; 16] = uuid(0x9e29_8b19_56db_4773_b23d_fc3e_c6f0_a1e6);

const fn uuid(value: u128) -> [u8; 16] {
    value.to_be_bytes()
}

pub(crate) const SIGNATURE: [u8; 8] = [0x03, 0xd9, 0xa2, 0x9a, 0x67, 0xfb, 0x4b, 0xb5];
pub(crate) const FIELD_MASTER_SEED: u8 = 4;
pub(crate) const FIELD_IV: u8 = 7;
pub(crate) const FIELD_COMPRESSION: u8 = 3;
pub(super) const FIELD_CIPHER: u8 = 2;
pub(super) const FIELD_KDF: u8 = 11;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Compression {
    None,
    Gzip,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum KdfParams<'a> {
    Aes {
        seed: &'a [u8; 32],
        rounds: u64,
    },
    Argon2 {
        kind: Kdf,
        salt: &'a [u8],
        memory_bytes: u64,
        iterations: u64,
        parallelism: u32,
        version: u32,
    },
}

impl KdfParams<'_> {
    pub(crate) fn kind(&self) -> Kdf {
        match self {
            Self::Aes { .. } => Kdf::AesKdf,
            Self::Argon2 { kind, .. } => *kind,
        }
    }
}

#[derive(Debug)]
pub(crate) struct OuterHeader<'a> {
    pub(crate) version: KdbxVersion,
    pub(crate) cipher: Cipher,
    pub(crate) compression: Compression,
    pub(crate) master_seed: &'a [u8],
    pub(crate) iv: &'a [u8],
    pub(crate) kdf: KdfParams<'a>,
    pub(crate) raw: &'a [u8],
    /// Every field except the end marker, in file order, for writing back unchanged.
    pub(crate) fields: Vec<(u8, &'a [u8])>,
}

pub(crate) fn parse<'a>(data: &'a [u8], limits: &OpenLimits) -> Result<OuterHeader<'a>, OpenError> {
    let mut reader = ByteReader::new(data);
    reader.take(8, ERR)?;
    let minor = reader.u16(ERR)?;
    let major = reader.u16(ERR)?;
    if major != 4 {
        return Err(OpenError::UnsupportedVersion { major, minor });
    }

    let mut cipher = None;
    let mut compression = None;
    let mut master_seed = None;
    let mut iv = None;
    let mut kdf = None;
    let mut fields = Vec::new();
    loop {
        let id = reader.u8(ERR)?;
        let size = reader.len_u32(ERR)?;
        let value = reader.take(size, ERR)?;
        if id != 0 {
            fields.push((id, value));
        }
        match id {
            0 => break,
            2 => set_once(&mut cipher, parse_cipher(value)?)?,
            3 => set_once(&mut compression, parse_compression(value)?)?,
            4 => set_once(&mut master_seed, value)?,
            7 => set_once(&mut iv, value)?,
            11 => set_once(&mut kdf, parse_kdf(value, limits)?)?,
            _ => {}
        }
    }

    let cipher = cipher.ok_or(ERR)?;
    let master_seed = master_seed.filter(|seed| seed.len() == 32).ok_or(ERR)?;
    let iv = iv.ok_or(ERR)?;
    let expected_iv = match cipher {
        Cipher::Aes256 => 16,
        Cipher::ChaCha20 => 12,
    };
    if iv.len() != expected_iv {
        return Err(ERR);
    }

    Ok(OuterHeader {
        version: KdbxVersion { major, minor },
        cipher,
        compression: compression.unwrap_or(Compression::None),
        master_seed,
        iv,
        kdf: kdf.ok_or(ERR)?,
        raw: &data[..reader.position()],
        fields,
    })
}

fn set_once<T>(slot: &mut Option<T>, value: T) -> Result<(), OpenError> {
    if slot.replace(value).is_some() {
        return Err(ERR);
    }
    Ok(())
}

fn parse_cipher(value: &[u8]) -> Result<Cipher, OpenError> {
    match <[u8; 16]>::try_from(value).map_err(|_| ERR)? {
        CIPHER_AES256 => Ok(Cipher::Aes256),
        CIPHER_CHACHA20 => Ok(Cipher::ChaCha20),
        _ => Err(OpenError::UnsupportedCipher),
    }
}

fn parse_compression(value: &[u8]) -> Result<Compression, OpenError> {
    match u32::from_le_bytes(value.try_into().map_err(|_| ERR)?) {
        0 => Ok(Compression::None),
        1 => Ok(Compression::Gzip),
        _ => Err(OpenError::UnsupportedCompression),
    }
}

fn parse_kdf<'a>(value: &'a [u8], limits: &OpenLimits) -> Result<KdfParams<'a>, OpenError> {
    let dict = VariantDictionary::parse(value)?;
    let id: [u8; 16] = dict.bytes("$UUID")?.try_into().map_err(|_| ERR)?;
    match id {
        KDF_AES => {
            let seed = dict.bytes("S")?.try_into().map_err(|_| ERR)?;
            let rounds = dict.u64("R")?;
            if rounds > limits.max_aes_kdf_rounds {
                return Err(OpenError::LimitExceeded(Limit::AesKdfRounds));
            }
            Ok(KdfParams::Aes { seed, rounds })
        }
        KDF_ARGON2D | KDF_ARGON2ID => {
            if dict.contains("K") || dict.contains("A") {
                return Err(OpenError::UnsupportedKdf);
            }
            let salt = dict.bytes("S")?;
            if !(8..=1024).contains(&salt.len()) {
                return Err(ERR);
            }
            let memory_bytes = dict.u64("M")?;
            let iterations = dict.u64("I")?;
            let parallelism = dict.u32("P")?;
            let version = dict.u32("V")?;
            if !matches!(version, 0x10 | 0x13) {
                return Err(OpenError::UnsupportedKdf);
            }
            if memory_bytes > limits.max_kdf_memory_bytes {
                return Err(OpenError::LimitExceeded(Limit::KdfMemory));
            }
            if iterations > limits.max_kdf_iterations {
                return Err(OpenError::LimitExceeded(Limit::KdfIterations));
            }
            if parallelism > limits.max_kdf_parallelism {
                return Err(OpenError::LimitExceeded(Limit::KdfParallelism));
            }
            let kind = if id == KDF_ARGON2D {
                Kdf::Argon2d
            } else {
                Kdf::Argon2id
            };
            Ok(KdfParams::Argon2 {
                kind,
                salt,
                memory_bytes,
                iterations,
                parallelism,
                version,
            })
        }
        _ => Err(OpenError::UnsupportedKdf),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uuid_constants_match_spec_byte_order() {
        assert_eq!(&CIPHER_AES256[..4], &[0x31, 0xc1, 0xf2, 0xe6]);
        assert_eq!(&KDF_ARGON2D[12..], &[0x03, 0xe3, 0x0a, 0x0c]);
    }

    #[test]
    fn rejects_non_v4_before_reading_fields() {
        let data = [
            0x03, 0xd9, 0xa2, 0x9a, 0x67, 0xfb, 0x4b, 0xb5, 0x01, 0x00, 0x03, 0x00,
        ];
        assert_eq!(
            parse(&data, &OpenLimits::default()).unwrap_err(),
            OpenError::UnsupportedVersion { major: 3, minor: 1 }
        );
    }

    #[test]
    fn rejects_field_length_past_end() {
        let mut data = vec![
            0x03, 0xd9, 0xa2, 0x9a, 0x67, 0xfb, 0x4b, 0xb5, 0x00, 0x00, 0x04, 0x00,
        ];
        data.push(2);
        data.extend_from_slice(&u32::MAX.to_le_bytes());
        assert_eq!(parse(&data, &OpenLimits::default()).unwrap_err(), ERR);
    }
}
