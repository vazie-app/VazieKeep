use crate::error::OpenError;

pub(crate) struct ByteReader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> ByteReader<'a> {
    pub(crate) fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    pub(crate) fn position(&self) -> usize {
        self.pos
    }

    pub(crate) fn remaining(&self) -> &'a [u8] {
        &self.data[self.pos..]
    }

    pub(crate) fn take(&mut self, len: usize, error: OpenError) -> Result<&'a [u8], OpenError> {
        let end = self
            .pos
            .checked_add(len)
            .filter(|&end| end <= self.data.len())
            .ok_or(error)?;
        let slice = &self.data[self.pos..end];
        self.pos = end;
        Ok(slice)
    }

    pub(crate) fn u8(&mut self, error: OpenError) -> Result<u8, OpenError> {
        Ok(self.take(1, error)?[0])
    }

    pub(crate) fn u16(&mut self, error: OpenError) -> Result<u16, OpenError> {
        let bytes = self.take(2, error)?;
        Ok(u16::from_le_bytes([bytes[0], bytes[1]]))
    }

    pub(crate) fn u32(&mut self, error: OpenError) -> Result<u32, OpenError> {
        let bytes = self.take(4, error)?;
        Ok(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    pub(crate) fn len_u32(&mut self, error: OpenError) -> Result<usize, OpenError> {
        usize::try_from(self.u32(error)?).map_err(|_| error)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn take_rejects_lengths_beyond_input() {
        let mut reader = ByteReader::new(&[1, 2, 3]);
        assert_eq!(
            reader.take(usize::MAX, OpenError::CorruptedVault),
            Err(OpenError::CorruptedVault)
        );
        assert_eq!(
            reader.take(4, OpenError::CorruptedVault),
            Err(OpenError::CorruptedVault)
        );
        assert_eq!(reader.take(2, OpenError::CorruptedVault), Ok(&[1, 2][..]));
        assert_eq!(reader.remaining(), &[3]);
    }
}
