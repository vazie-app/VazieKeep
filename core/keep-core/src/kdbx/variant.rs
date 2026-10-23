use super::reader::ByteReader;
use crate::error::OpenError;

const ERR: OpenError = OpenError::InvalidFormat;
const MAX_ITEMS: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Variant<'a> {
    U32(u32),
    U64(u64),
    Bool(bool),
    I32(i32),
    I64(i64),
    Str(&'a str),
    Bytes(&'a [u8]),
}

#[derive(Debug)]
pub(crate) struct VariantDictionary<'a> {
    items: Vec<(&'a str, Variant<'a>)>,
}

impl<'a> VariantDictionary<'a> {
    pub(crate) fn parse(data: &'a [u8]) -> Result<Self, OpenError> {
        let mut reader = ByteReader::new(data);
        let version = reader.u16(ERR)?;
        if version & 0xFF00 != 0x0100 {
            return Err(ERR);
        }
        let mut items: Vec<(&str, Variant<'_>)> = Vec::new();
        loop {
            let kind = reader.u8(ERR)?;
            if kind == 0 {
                break;
            }
            if items.len() == MAX_ITEMS {
                return Err(ERR);
            }
            let key_len = reader.len_u32(ERR)?;
            let key = std::str::from_utf8(reader.take(key_len, ERR)?).map_err(|_| ERR)?;
            let value_len = reader.len_u32(ERR)?;
            let value = reader.take(value_len, ERR)?;
            let variant = match (kind, value.len()) {
                (0x04, 4) => Variant::U32(u32::from_le_bytes(array(value)?)),
                (0x05, 8) => Variant::U64(u64::from_le_bytes(array(value)?)),
                (0x08, 1) => Variant::Bool(value[0] != 0),
                (0x0C, 4) => Variant::I32(i32::from_le_bytes(array(value)?)),
                (0x0D, 8) => Variant::I64(i64::from_le_bytes(array(value)?)),
                (0x18, _) => Variant::Str(std::str::from_utf8(value).map_err(|_| ERR)?),
                (0x42, _) => Variant::Bytes(value),
                _ => return Err(ERR),
            };
            if items.iter().any(|(existing, _)| *existing == key) {
                return Err(ERR);
            }
            items.push((key, variant));
        }
        Ok(Self { items })
    }

    pub(crate) fn get(&self, key: &str) -> Option<Variant<'a>> {
        self.items.iter().find(|(k, _)| *k == key).map(|(_, v)| *v)
    }

    pub(crate) fn contains(&self, key: &str) -> bool {
        self.get(key).is_some()
    }

    pub(crate) fn bytes(&self, key: &str) -> Result<&'a [u8], OpenError> {
        match self.get(key) {
            Some(Variant::Bytes(value)) => Ok(value),
            _ => Err(ERR),
        }
    }

    pub(crate) fn u32(&self, key: &str) -> Result<u32, OpenError> {
        match self.get(key) {
            Some(Variant::U32(value)) => Ok(value),
            _ => Err(ERR),
        }
    }

    pub(crate) fn u64(&self, key: &str) -> Result<u64, OpenError> {
        match self.get(key) {
            Some(Variant::U64(value)) => Ok(value),
            _ => Err(ERR),
        }
    }
}

fn array<const N: usize>(value: &[u8]) -> Result<[u8; N], OpenError> {
    value.try_into().map_err(|_| ERR)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(kind: u8, key: &str, value: &[u8]) -> Vec<u8> {
        let mut out = vec![kind];
        out.extend_from_slice(&u32::try_from(key.len()).unwrap().to_le_bytes());
        out.extend_from_slice(key.as_bytes());
        out.extend_from_slice(&u32::try_from(value.len()).unwrap().to_le_bytes());
        out.extend_from_slice(value);
        out
    }

    #[test]
    fn parses_typed_items() {
        let mut data = vec![0x00, 0x01];
        data.extend(item(0x05, "R", &60_000u64.to_le_bytes()));
        data.extend(item(0x42, "S", &[7; 32]));
        data.push(0);
        let dict = VariantDictionary::parse(&data).unwrap();
        assert_eq!(dict.u64("R"), Ok(60_000));
        assert_eq!(dict.bytes("S"), Ok(&[7u8; 32][..]));
        assert_eq!(dict.u32("R"), Err(ERR));
    }

    #[test]
    fn rejects_duplicates_wrong_sizes_and_unknown_versions() {
        let mut duplicate = vec![0x00, 0x01];
        duplicate.extend(item(0x04, "P", &2u32.to_le_bytes()));
        duplicate.extend(item(0x04, "P", &2u32.to_le_bytes()));
        duplicate.push(0);
        assert!(VariantDictionary::parse(&duplicate).is_err());

        let mut wrong_size = vec![0x00, 0x01];
        wrong_size.extend(item(0x05, "I", &[1, 2, 3]));
        wrong_size.push(0);
        assert!(VariantDictionary::parse(&wrong_size).is_err());

        assert!(VariantDictionary::parse(&[0x00, 0x02, 0x00]).is_err());
        assert!(VariantDictionary::parse(&[0x00, 0x01]).is_err());
    }
}
