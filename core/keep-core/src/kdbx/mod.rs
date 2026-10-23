pub(crate) mod create;
mod crypto;
pub(crate) mod dom;
pub(crate) mod edit;
mod header;
mod inner;
pub(crate) mod merge;
mod reader;
pub(crate) mod time;
mod variant;
pub(crate) mod writer;
pub(crate) mod xml;

use std::io::Read;

use flate2::read::GzDecoder;
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use crate::credentials::VaultCredentials;
use crate::error::{Limit, OpenError};
use crate::format::{VaultFormat, detect_format};
use crate::limits::OpenLimits;
use crate::model::{Vault, VaultMetadata};
use dom::Element;
use header::Compression;
use reader::ByteReader;
pub(crate) use writer::{SaveState, VaultSource, WriteError};

/// The payload document and the attachments, as stored.
pub(crate) type Decrypted = (Element, Vec<Zeroizing<Vec<u8>>>);

pub(crate) fn open(
    data: &[u8],
    credentials: &VaultCredentials,
    limits: &OpenLimits,
) -> Result<Vault, OpenError> {
    let header = check_header(data, limits)?;
    let composite = credentials.composite_key()?;
    let transformed = crypto::transform_key(&header.kdf, &composite)?;
    open_decrypted(data, &header, transformed, limits)
}

/// Opens another version of a database with the transformed key an open session already holds, so no
/// password is needed. It fits only a file written with the same key derivation parameters (another
/// device running this core); a file whose parameters or password changed fails as `InvalidCredentials`.
pub(crate) fn open_with_transformed_key(
    data: &[u8],
    transformed: &[u8; 32],
    limits: &OpenLimits,
) -> Result<Vault, OpenError> {
    let header = check_header(data, limits)?;
    open_decrypted(data, &header, Zeroizing::new(*transformed), limits)
}

fn open_decrypted(
    data: &[u8],
    header: &header::OuterHeader<'_>,
    transformed: Zeroizing<[u8; 32]>,
    limits: &OpenLimits,
) -> Result<Vault, OpenError> {
    let (document, binaries) = decrypt(data, header, &transformed, limits)?;
    let source = VaultSource {
        document,
        save: SaveState {
            version: header.version,
            cipher: header.cipher,
            compression: header.compression,
            fields: header
                .fields
                .iter()
                .filter(|(id, _)| !matches!(*id, header::FIELD_MASTER_SEED | header::FIELD_IV))
                .map(|(id, value)| (*id, value.to_vec()))
                .collect(),
            binaries,
            transformed_key: transformed,
        },
    };
    let attachment_sizes: Vec<usize> = source.save.binaries.iter().map(|b| b.len() - 1).collect();
    let (metadata, root) = xml::project(&source.document, &attachment_sizes, limits)?;
    Ok(Vault {
        metadata: VaultMetadata {
            format: header.version,
            cipher: header.cipher,
            kdf: header.kdf.kind(),
            name: metadata.name,
            description: metadata.description,
            generator: metadata.generator,
            recycle_bin: metadata.recycle_bin,
        },
        root,
        source: Some(Box::new(source)),
    })
}

/// Reads bytes this process just wrote, with the transformed key kept from the unlock.
pub(crate) fn read_with_key(
    data: &[u8],
    transformed: &[u8; 32],
    limits: &OpenLimits,
) -> Result<Decrypted, OpenError> {
    let header = check_header(data, limits)?;
    decrypt(data, &header, transformed, limits)
}

fn check_header<'a>(
    data: &'a [u8],
    limits: &OpenLimits,
) -> Result<header::OuterHeader<'a>, OpenError> {
    if data.len() > limits.max_file_size {
        return Err(OpenError::LimitExceeded(Limit::FileSize));
    }
    match detect_format(data) {
        VaultFormat::Kdbx { major: 4, .. } => {}
        VaultFormat::Kdbx { major, minor } => {
            return Err(OpenError::UnsupportedVersion { major, minor });
        }
        VaultFormat::KeePass1 => return Err(OpenError::UnsupportedVersion { major: 1, minor: 0 }),
        VaultFormat::Unknown => return Err(OpenError::InvalidFormat),
    }
    header::parse(data, limits)
}

fn decrypt(
    data: &[u8],
    header: &header::OuterHeader<'_>,
    transformed: &[u8; 32],
    limits: &OpenLimits,
) -> Result<Decrypted, OpenError> {
    let mut reader = ByteReader::new(&data[header.raw.len()..]);
    let header_hash = reader.take(32, OpenError::CorruptedVault)?;
    if Sha256::digest(header.raw).as_slice() != header_hash {
        return Err(OpenError::CorruptedVault);
    }
    let header_mac = reader.take(32, OpenError::CorruptedVault)?;
    let keys = crypto::DerivedKeys::derive(header.master_seed, transformed);
    keys.verify_header(header.raw, header_mac)?;

    let mut payload = keys.read_blocks(reader.remaining())?;
    crypto::decrypt_payload(header.cipher, &keys.cipher_key, header.iv, &mut payload)?;
    let payload = match header.compression {
        Compression::None => payload,
        Compression::Gzip => gunzip(&payload, limits)?,
    };
    let mut inner = inner::parse(&payload, limits)?;
    let document = dom::parse(&payload[inner.xml_offset..], &mut inner.stream, limits)?;
    Ok((document, inner.binaries))
}

#[cfg(feature = "fuzzing")]
fn parse_payload(payload: &[u8], limits: &OpenLimits) -> Result<(), OpenError> {
    let mut inner = inner::parse(payload, limits)?;
    let document = dom::parse(&payload[inner.xml_offset..], &mut inner.stream, limits)?;
    xml::project(&document, &inner.attachment_sizes(), limits)?;
    let _ = dom::write(&document, &mut crypto::ProtectedStream::none());
    Ok(())
}

fn gunzip(data: &[u8], limits: &OpenLimits) -> Result<Zeroizing<Vec<u8>>, OpenError> {
    const MAX_DEFLATE_RATIO: usize = 1032;
    let limit = limits.max_decompressed_size;
    let declared = data
        .len()
        .checked_sub(4)
        .and_then(|at| data[at..].try_into().ok())
        .map_or(0, |trailer: [u8; 4]| u32::from_le_bytes(trailer) as usize);
    let capacity = declared
        .min(limit)
        .min(data.len().saturating_mul(MAX_DEFLATE_RATIO));
    let mut out = Zeroizing::new(Vec::with_capacity(capacity));
    GzDecoder::new(data)
        .take(u64::try_from(limit).unwrap_or(u64::MAX).saturating_add(1))
        .read_to_end(&mut out)
        .map_err(|_| OpenError::DecompressionFailed)?;
    if out.len() > limit {
        return Err(OpenError::LimitExceeded(Limit::DecompressedSize));
    }
    Ok(out)
}

#[cfg(feature = "fuzzing")]
pub(crate) fn fuzz_header(data: &[u8]) {
    let _ = header::parse(data, &fuzz_limits());
}

#[cfg(feature = "fuzzing")]
pub(crate) fn fuzz_payload(data: &[u8]) {
    let _ = parse_payload(data, &fuzz_limits());
}

#[cfg(feature = "fuzzing")]
pub(crate) fn fuzz_xml_round_trip(data: &[u8]) {
    let limits = fuzz_limits();
    let Ok(document) = dom::parse(data, &mut crypto::ProtectedStream::none(), &limits) else {
        return;
    };
    let key = [7u8; 64];
    let written = dom::write(
        &document,
        &mut crypto::ProtectedStream::chacha20(&key).expect("stream"),
    );
    let again = dom::parse(
        &written,
        &mut crypto::ProtectedStream::chacha20(&key).expect("stream"),
        &limits,
    )
    .expect("written XML parses");
    assert!(document.same_as(&again), "round trip changed the document");
}

#[cfg(feature = "fuzzing")]
fn fuzz_limits() -> OpenLimits {
    OpenLimits {
        max_file_size: 1 << 20,
        max_decompressed_size: 1 << 22,
        max_kdf_memory_bytes: 64 * 1024,
        max_kdf_iterations: 2,
        max_kdf_parallelism: 2,
        max_aes_kdf_rounds: 16,
        max_xml_nodes: 100_000,
        ..OpenLimits::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn gunzip_stops_at_limit() {
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::best());
        encoder.write_all(&vec![0u8; 1 << 20]).unwrap();
        let bomb = encoder.finish().unwrap();
        let limits = OpenLimits {
            max_decompressed_size: 4096,
            ..OpenLimits::default()
        };
        assert_eq!(
            gunzip(&bomb, &limits).unwrap_err(),
            OpenError::LimitExceeded(Limit::DecompressedSize)
        );
        assert_eq!(
            gunzip(&bomb, &OpenLimits::default()).unwrap().len(),
            1 << 20
        );
        assert_eq!(
            gunzip(b"not gzip", &limits).unwrap_err(),
            OpenError::DecompressionFailed
        );
    }
}
