//! KDBX 4.x writer.
//!
//! What a save keeps from the file that was opened: the version, every outer header field
//! (cipher, compression, KDF parameters including the KDF salt, public custom data) and every
//! attachment, byte for byte. What it makes fresh, from the system CSPRNG: the master seed, the
//! encryption IV and the inner-stream key. Because the KDF parameters are kept, the transformed
//! key from the unlock still applies, and saving never needs the password or a KDF run.

use std::cell::RefCell;
use std::fmt;
use std::io::Write as _;

use flate2::Compression as Level;
use flate2::write::GzEncoder;
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use super::crypto::{self, DerivedKeys, ProtectedStream};
use super::dom::{self, Element};
use super::header::{self, Compression};
use crate::limits::OpenLimits;
use crate::model::{Cipher, KdbxVersion};

const INNER_STREAM_CHACHA20: u32 = 3;

thread_local! {
    static LAST_DIAGNOSTIC: RefCell<Option<String>> = const { RefCell::new(None) };
}

fn note(text: Option<String>) {
    LAST_DIAGNOSTIC.with(|slot| *slot.borrow_mut() = text);
}

/// What the last failed save check found, in words and without secrets; None if the last check passed.
pub(crate) fn last_diagnostic() -> Option<String> {
    LAST_DIAGNOSTIC.with(|slot| slot.borrow().clone())
}

pub(crate) struct SaveState {
    pub(crate) version: KdbxVersion,
    pub(crate) cipher: Cipher,
    pub(crate) compression: Compression,
    /// Outer header fields except the master seed and IV, in file order.
    pub(crate) fields: Vec<(u8, Vec<u8>)>,
    pub(crate) binaries: Vec<Zeroizing<Vec<u8>>>,
    pub(crate) transformed_key: Zeroizing<[u8; 32]>,
}

impl fmt::Debug for SaveState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SaveState")
            .field("version", &self.version)
            .field("cipher", &self.cipher)
            .field("compression", &self.compression)
            .field("binaries", &self.binaries.len())
            .finish_non_exhaustive()
    }
}

/// Everything a save needs: the lossless document and how the file was written.
#[derive(Debug)]
pub(crate) struct VaultSource {
    pub(crate) document: Element,
    pub(crate) save: SaveState,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WriteError {
    RandomUnavailable,
    EncryptionFailed,
    /// The bytes just written don't read back as the same vault. Nothing should be written.
    VerificationFailed,
}

impl VaultSource {
    /// Serializes, compresses, encrypts and authenticates the vault, then reads the result back
    /// with the regular reader and compares it with what was meant to be written.
    pub(crate) fn write_verified(&self, limits: &OpenLimits) -> Result<Vec<u8>, WriteError> {
        let bytes = self.write()?;
        let (document, binaries) = super::read_with_key(&bytes, &self.save.transformed_key, limits)
            .map_err(|error| {
                note(Some(format!(
                    "the written bytes could not be read back: {error:?}"
                )));
                WriteError::VerificationFailed
            })?;
        let same_binaries = binaries.len() == self.save.binaries.len()
            && binaries
                .iter()
                .zip(&self.save.binaries)
                .all(|(a, b)| a.as_slice() == b.as_slice());
        if !document.same_as(&self.document) || !same_binaries {
            note(Some(if document.same_as(&self.document) {
                format!(
                    "attachments differ after read-back ({} written, {} read)",
                    self.save.binaries.len(),
                    binaries.len()
                )
            } else {
                self.document
                    .first_difference(&document)
                    .unwrap_or_else(|| "the document differs after read-back".to_owned())
            }));
            return Err(WriteError::VerificationFailed);
        }
        note(None);
        Ok(bytes)
    }

    fn write(&self) -> Result<Vec<u8>, WriteError> {
        const FAILED: WriteError = WriteError::EncryptionFailed;
        let save = &self.save;
        let master_seed = random::<32>()?;
        let iv: Zeroizing<Vec<u8>> = match save.cipher {
            Cipher::Aes256 => Zeroizing::new(random::<16>()?.to_vec()),
            Cipher::ChaCha20 => Zeroizing::new(random::<12>()?.to_vec()),
        };
        let stream_key = random::<64>()?;

        let mut payload = Zeroizing::new(Vec::with_capacity(64 * 1024));
        push_field(&mut payload, 1, &INNER_STREAM_CHACHA20.to_le_bytes());
        push_field(&mut payload, 2, stream_key.as_slice());
        for binary in &save.binaries {
            push_field(&mut payload, 3, binary);
        }
        push_field(&mut payload, 0, &[]);
        let mut stream = ProtectedStream::chacha20(stream_key.as_slice()).map_err(|_| FAILED)?;
        payload.extend_from_slice(&dom::write(&self.document, &mut stream));

        let payload = match save.compression {
            Compression::None => payload,
            Compression::Gzip => {
                // Sized up front so the buffer never reallocates and leaves stray copies behind.
                let mut encoder =
                    GzEncoder::new(Vec::with_capacity(payload.len() + 4096), Level::default());
                encoder.write_all(&payload).map_err(|_| FAILED)?;
                Zeroizing::new(encoder.finish().map_err(|_| FAILED)?)
            }
        };

        let mut out = Vec::with_capacity(payload.len() + 4096);
        out.extend_from_slice(&header::SIGNATURE);
        out.extend_from_slice(&save.version.minor.to_le_bytes());
        out.extend_from_slice(&save.version.major.to_le_bytes());
        let mut wrote_seed = false;
        for (id, value) in &save.fields {
            push_header_field(&mut out, *id, value);
            if *id == header::FIELD_COMPRESSION && !wrote_seed {
                push_header_field(&mut out, header::FIELD_MASTER_SEED, master_seed.as_slice());
                push_header_field(&mut out, header::FIELD_IV, &iv);
                wrote_seed = true;
            }
        }
        if !wrote_seed {
            push_header_field(&mut out, header::FIELD_MASTER_SEED, master_seed.as_slice());
            push_header_field(&mut out, header::FIELD_IV, &iv);
        }
        push_header_field(&mut out, 0, b"\r\n\r\n");

        let keys = DerivedKeys::derive(master_seed.as_slice(), &save.transformed_key);
        let header_length = out.len();
        let hash = Sha256::digest(&out[..header_length]);
        let mac = keys.header_mac(&out[..header_length]).map_err(|_| FAILED)?;
        out.extend_from_slice(&hash);
        out.extend_from_slice(&mac);

        let ciphertext = crypto::encrypt_payload(save.cipher, &keys.cipher_key, &iv, payload)
            .map_err(|_| FAILED)?;
        keys.write_blocks(&ciphertext, &mut out)
            .map_err(|_| FAILED)?;
        Ok(out)
    }
}

pub(crate) fn random<const N: usize>() -> Result<Zeroizing<[u8; N]>, WriteError> {
    let mut bytes = Zeroizing::new([0u8; N]);
    getrandom::fill(bytes.as_mut_slice()).map_err(|_| WriteError::RandomUnavailable)?;
    Ok(bytes)
}

fn push_field(out: &mut Vec<u8>, id: u8, value: &[u8]) {
    out.push(id);
    out.extend_from_slice(&u32::try_from(value.len()).unwrap_or(u32::MAX).to_le_bytes());
    out.extend_from_slice(value);
}

fn push_header_field(out: &mut Vec<u8>, id: u8, value: &[u8]) {
    push_field(out, id, value);
}
