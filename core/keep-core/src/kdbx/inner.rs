use zeroize::Zeroizing;

use super::crypto::ProtectedStream;
use super::reader::ByteReader;
use crate::error::{Limit, OpenError};
use crate::limits::OpenLimits;

const ERR: OpenError = OpenError::CorruptedVault;

#[derive(Debug)]
pub(crate) struct InnerHeader {
    pub(crate) stream: ProtectedStream,
    /// Each binary as stored: one flags byte, then the content. Kept whole so a save writes
    /// attachments back unchanged.
    pub(crate) binaries: Vec<Zeroizing<Vec<u8>>>,
    pub(crate) xml_offset: usize,
}

impl InnerHeader {
    #[cfg(any(test, feature = "fuzzing"))]
    pub(crate) fn attachment_sizes(&self) -> Vec<usize> {
        self.binaries.iter().map(|b| b.len() - 1).collect()
    }
}

pub(crate) fn parse(payload: &[u8], limits: &OpenLimits) -> Result<InnerHeader, OpenError> {
    let mut reader = ByteReader::new(payload);
    let mut stream_id = None;
    let mut stream_key = None;
    let mut binaries = Vec::new();
    loop {
        let id = reader.u8(ERR)?;
        let size = reader.len_u32(ERR)?;
        let value = reader.take(size, ERR)?;
        match id {
            0 => break,
            1 => {
                let id = u32::from_le_bytes(value.try_into().map_err(|_| ERR)?);
                if stream_id.replace(id).is_some() {
                    return Err(ERR);
                }
            }
            2 => {
                if stream_key.replace(value).is_some() {
                    return Err(ERR);
                }
            }
            3 => {
                if value.is_empty() {
                    return Err(ERR);
                }
                if binaries.len() == limits.max_attachments {
                    return Err(OpenError::LimitExceeded(Limit::Attachments));
                }
                binaries.push(Zeroizing::new(value.to_vec()));
            }
            _ => return Err(ERR),
        }
    }

    let stream = match (stream_id, stream_key) {
        (Some(3), Some(key)) if !key.is_empty() => ProtectedStream::chacha20(key)?,
        (Some(0) | None, _) => ProtectedStream::none(),
        (Some(2), _) => return Err(OpenError::UnsupportedCipher),
        _ => return Err(ERR),
    };

    Ok(InnerHeader {
        stream,
        binaries,
        xml_offset: reader.position(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn field(id: u8, value: &[u8]) -> Vec<u8> {
        let mut out = vec![id];
        out.extend_from_slice(&u32::try_from(value.len()).unwrap().to_le_bytes());
        out.extend_from_slice(value);
        out
    }

    #[test]
    fn parses_stream_and_binaries() {
        let mut payload = field(1, &3u32.to_le_bytes());
        payload.extend(field(2, &[9; 64]));
        payload.extend(field(3, &[1, b'a', b'b', b'c']));
        payload.extend(field(0, &[]));
        payload.extend_from_slice(b"<KeePassFile/>");
        let header = parse(&payload, &OpenLimits::default()).unwrap();
        assert_eq!(header.attachment_sizes(), vec![3]);
        assert_eq!(&payload[header.xml_offset..], b"<KeePassFile/>");
    }

    #[test]
    fn enforces_attachment_limit() {
        let mut payload = field(3, &[0, 1]);
        payload.extend(field(3, &[0, 2]));
        payload.extend(field(0, &[]));
        let limits = OpenLimits {
            max_attachments: 1,
            ..OpenLimits::default()
        };
        assert_eq!(
            parse(&payload, &limits).unwrap_err(),
            OpenError::LimitExceeded(Limit::Attachments)
        );
    }

    #[test]
    fn rejects_salsa20_and_truncation() {
        let mut salsa = field(1, &2u32.to_le_bytes());
        salsa.extend(field(2, &[1; 32]));
        salsa.extend(field(0, &[]));
        assert_eq!(
            parse(&salsa, &OpenLimits::default()).unwrap_err(),
            OpenError::UnsupportedCipher
        );
        assert_eq!(parse(&[1, 4, 0], &OpenLimits::default()).unwrap_err(), ERR);
    }
}
