const SIGNATURE_1: [u8; 4] = [0x03, 0xD9, 0xA2, 0x9A];
const SIGNATURE_2_KDBX: [u8; 4] = [0x67, 0xFB, 0x4B, 0xB5];
const SIGNATURE_2_KDB: [u8; 4] = [0x65, 0xFB, 0x4B, 0xB5];

pub const HEADER_PREFIX_LEN: usize = 12;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VaultFormat {
    Kdbx { major: u16, minor: u16 },
    KeePass1,
    Unknown,
}

pub fn detect_format(header: &[u8]) -> VaultFormat {
    let (Some(sig1), Some(sig2)) = (header.get(0..4), header.get(4..8)) else {
        return VaultFormat::Unknown;
    };
    if sig1 != SIGNATURE_1 {
        return VaultFormat::Unknown;
    }
    if sig2 == SIGNATURE_2_KDB {
        return VaultFormat::KeePass1;
    }
    if sig2 != SIGNATURE_2_KDBX {
        return VaultFormat::Unknown;
    }
    match header.get(8..HEADER_PREFIX_LEN) {
        Some(&[minor_lo, minor_hi, major_lo, major_hi]) => VaultFormat::Kdbx {
            major: u16::from_le_bytes([major_lo, major_hi]),
            minor: u16::from_le_bytes([minor_lo, minor_hi]),
        },
        _ => VaultFormat::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_input_is_unknown() {
        assert_eq!(detect_format(&[]), VaultFormat::Unknown);
    }

    #[test]
    fn ignores_bytes_after_prefix() {
        let mut header = vec![
            0x03, 0xD9, 0xA2, 0x9A, 0x67, 0xFB, 0x4B, 0xB5, 0x01, 0x00, 0x04, 0x00,
        ];
        header.extend_from_slice(&[0xFF; 32]);
        assert_eq!(
            detect_format(&header),
            VaultFormat::Kdbx { major: 4, minor: 1 }
        );
    }
}
