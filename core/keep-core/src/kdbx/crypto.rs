use aes::Aes256;
use aes::cipher::{
    BlockCipherEncrypt, BlockModeDecrypt, BlockModeEncrypt, KeyInit, KeyIvInit, StreamCipher,
    block_padding::Pkcs7,
};
use argon2::{Algorithm, Argon2, Params, Version};
use chacha20::ChaCha20;
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256, Sha512};
use zeroize::{Zeroize, Zeroizing};

use super::header::KdfParams;
use super::reader::ByteReader;
use crate::error::OpenError;
use crate::model::{Cipher, Kdf};

type HmacSha256 = Hmac<Sha256>;

pub(crate) fn transform_key(
    params: &KdfParams<'_>,
    composite: &[u8; 32],
) -> Result<Zeroizing<[u8; 32]>, OpenError> {
    match *params {
        KdfParams::Aes { seed, rounds } => Ok(aes_kdf(seed, rounds, composite)),
        KdfParams::Argon2 {
            kind,
            salt,
            memory_bytes,
            iterations,
            parallelism,
            version,
        } => {
            let algorithm = if kind == Kdf::Argon2d {
                Algorithm::Argon2d
            } else {
                Algorithm::Argon2id
            };
            let version = if version == 0x10 {
                Version::V0x10
            } else {
                Version::V0x13
            };
            let m_cost =
                u32::try_from(memory_bytes / 1024).map_err(|_| OpenError::InvalidFormat)?;
            let t_cost = u32::try_from(iterations).map_err(|_| OpenError::InvalidFormat)?;
            let params = Params::new(m_cost, t_cost, parallelism, Some(32))
                .map_err(|_| OpenError::InvalidFormat)?;
            let mut out = Zeroizing::new([0u8; 32]);
            Argon2::new(algorithm, version, params)
                .hash_password_into(composite, salt, out.as_mut_slice())
                .map_err(|_| OpenError::InvalidFormat)?;
            Ok(out)
        }
    }
}

fn aes_kdf(seed: &[u8; 32], rounds: u64, composite: &[u8; 32]) -> Zeroizing<[u8; 32]> {
    let cipher = Aes256::new(&(*seed).into());
    let mut blocks = [aes::Block::default(), aes::Block::default()];
    blocks[0].copy_from_slice(&composite[..16]);
    blocks[1].copy_from_slice(&composite[16..]);
    for _ in 0..rounds {
        cipher.encrypt_blocks(&mut blocks);
    }
    let mut hasher = Sha256::new();
    hasher.update(blocks[0].as_slice());
    hasher.update(blocks[1].as_slice());
    for block in &mut blocks {
        block.as_mut_slice().zeroize();
    }
    copy_array(&hasher.finalize())
}

pub(crate) struct DerivedKeys {
    pub(crate) cipher_key: Zeroizing<[u8; 32]>,
    hmac_base: Zeroizing<[u8; 64]>,
}

impl DerivedKeys {
    pub(crate) fn derive(master_seed: &[u8], transformed: &[u8; 32]) -> Self {
        let cipher_key = copy_array(
            &Sha256::new()
                .chain_update(master_seed)
                .chain_update(transformed)
                .finalize(),
        );
        let hmac_base = copy_array(
            &Sha512::new()
                .chain_update(master_seed)
                .chain_update(transformed)
                .chain_update([1u8])
                .finalize(),
        );
        Self {
            cipher_key,
            hmac_base,
        }
    }

    fn block_mac(&self, index: u64) -> Result<HmacSha256, OpenError> {
        let key: Zeroizing<[u8; 64]> = copy_array(
            &Sha512::new()
                .chain_update(index.to_le_bytes())
                .chain_update(self.hmac_base.as_slice())
                .finalize(),
        );
        HmacSha256::new_from_slice(key.as_slice()).map_err(|_| OpenError::CorruptedVault)
    }

    pub(crate) fn verify_header(&self, header: &[u8], tag: &[u8]) -> Result<(), OpenError> {
        let mut mac = self.block_mac(u64::MAX)?;
        mac.update(header);
        mac.verify_slice(tag)
            .map_err(|_| OpenError::InvalidCredentials)
    }

    pub(crate) fn header_mac(&self, header: &[u8]) -> Result<[u8; 32], OpenError> {
        let mut mac = self.block_mac(u64::MAX)?;
        mac.update(header);
        Ok(mac.finalize().into_bytes().into())
    }

    /// The HMAC-SHA-256 block stream: 1 MiB blocks, then an empty terminating block.
    pub(crate) fn write_blocks(&self, data: &[u8], out: &mut Vec<u8>) -> Result<(), OpenError> {
        const BLOCK: usize = 1 << 20;
        for (index, block) in (0u64..).zip(data.chunks(BLOCK).chain(std::iter::once(&[][..]))) {
            let length = u32::try_from(block.len())
                .map_err(|_| OpenError::CorruptedVault)?
                .to_le_bytes();
            let mut mac = self.block_mac(index)?;
            mac.update(&index.to_le_bytes());
            mac.update(&length);
            mac.update(block);
            out.extend_from_slice(&mac.finalize().into_bytes());
            out.extend_from_slice(&length);
            out.extend_from_slice(block);
        }
        Ok(())
    }

    pub(crate) fn read_blocks(&self, data: &[u8]) -> Result<Zeroizing<Vec<u8>>, OpenError> {
        const ERR: OpenError = OpenError::CorruptedVault;
        let mut reader = ByteReader::new(data);
        let mut blocks = Vec::new();
        let mut total = 0usize;
        for index in 0u64.. {
            let tag = reader.take(32, ERR)?;
            let length_bytes = reader.take(4, ERR)?;
            let length = u32::from_le_bytes(length_bytes.try_into().map_err(|_| ERR)?);
            if i32::try_from(length).is_err() {
                return Err(ERR);
            }
            let block = reader.take(usize::try_from(length).map_err(|_| ERR)?, ERR)?;
            let mut mac = self.block_mac(index)?;
            mac.update(&index.to_le_bytes());
            mac.update(length_bytes);
            mac.update(block);
            mac.verify_slice(tag)
                .map_err(|_| OpenError::IntegrityCheckFailed)?;
            if block.is_empty() {
                break;
            }
            total = total.checked_add(block.len()).ok_or(ERR)?;
            blocks.push(block);
        }
        let mut out = Zeroizing::new(Vec::with_capacity(total));
        for block in blocks {
            out.extend_from_slice(block);
        }
        Ok(out)
    }
}

pub(crate) fn decrypt_payload(
    cipher: Cipher,
    key: &[u8; 32],
    iv: &[u8],
    data: &mut Zeroizing<Vec<u8>>,
) -> Result<(), OpenError> {
    match cipher {
        Cipher::Aes256 => {
            let decryptor = cbc::Decryptor::<Aes256>::new_from_slices(key, iv)
                .map_err(|_| OpenError::InvalidFormat)?;
            let length = decryptor
                .decrypt_padded::<Pkcs7>(data.as_mut_slice())
                .map_err(|_| OpenError::CorruptedVault)?
                .len();
            data.truncate(length);
        }
        Cipher::ChaCha20 => {
            let mut stream =
                ChaCha20::new_from_slices(key, iv).map_err(|_| OpenError::InvalidFormat)?;
            stream.apply_keystream(data.as_mut_slice());
        }
    }
    Ok(())
}

/// Encrypts the (compressed) payload in place and returns the ciphertext.
pub(crate) fn encrypt_payload(
    cipher: Cipher,
    key: &[u8; 32],
    iv: &[u8],
    mut data: Zeroizing<Vec<u8>>,
) -> Result<Vec<u8>, OpenError> {
    match cipher {
        Cipher::Aes256 => {
            let length = data.len();
            data.resize(length + 16, 0);
            let encryptor = cbc::Encryptor::<Aes256>::new_from_slices(key, iv)
                .map_err(|_| OpenError::InvalidFormat)?;
            let written = encryptor
                .encrypt_padded::<Pkcs7>(data.as_mut_slice(), length)
                .map_err(|_| OpenError::CorruptedVault)?
                .len();
            data.truncate(written);
        }
        Cipher::ChaCha20 => {
            let mut stream =
                ChaCha20::new_from_slices(key, iv).map_err(|_| OpenError::InvalidFormat)?;
            stream.apply_keystream(data.as_mut_slice());
        }
    }
    Ok(std::mem::take(&mut *data))
}

pub(crate) struct ProtectedStream(Option<ChaCha20>);

impl ProtectedStream {
    pub(crate) fn none() -> Self {
        Self(None)
    }

    pub(crate) fn chacha20(key: &[u8]) -> Result<Self, OpenError> {
        let hash: Zeroizing<[u8; 64]> = copy_array(&Sha512::digest(key));
        let stream = ChaCha20::new_from_slices(&hash[..32], &hash[32..44])
            .map_err(|_| OpenError::CorruptedVault)?;
        Ok(Self(Some(stream)))
    }

    pub(crate) fn apply(&mut self, buf: &mut [u8]) {
        if let Some(stream) = &mut self.0 {
            stream.apply_keystream(buf);
        }
    }
}

impl std::fmt::Debug for ProtectedStream {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("ProtectedStream")
    }
}

fn copy_array<const N: usize>(digest: &[u8]) -> Zeroizing<[u8; N]> {
    let mut out = Zeroizing::new([0u8; N]);
    out.copy_from_slice(digest);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aes_kdf_zero_rounds_is_sha256_of_composite() {
        let composite = [7u8; 32];
        let out = aes_kdf(&[1u8; 32], 0, &composite);
        assert_eq!(out.as_slice(), Sha256::digest(composite).as_slice());
    }

    #[test]
    fn argon2_is_deterministic_and_key_dependent() {
        let params = KdfParams::Argon2 {
            kind: Kdf::Argon2id,
            salt: &[2u8; 16],
            memory_bytes: 32 * 1024,
            iterations: 3,
            parallelism: 4,
            version: 0x13,
        };
        let a = transform_key(&params, &[1u8; 32]).unwrap();
        let b = transform_key(&params, &[1u8; 32]).unwrap();
        let c = transform_key(&params, &[3u8; 32]).unwrap();
        assert_eq!(a.as_slice(), b.as_slice());
        assert_ne!(a.as_slice(), c.as_slice());
    }
}
