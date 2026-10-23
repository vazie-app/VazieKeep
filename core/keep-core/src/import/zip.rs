//! The little of ZIP that a `.1pux` file needs: find one member by name and inflate it, with a
//! ceiling on what comes out. No ZIP64, no encryption, no multi-part archives.

use std::io::Read;

use flate2::read::DeflateDecoder;
use zeroize::Zeroizing;

const END_OF_DIRECTORY: [u8; 4] = [0x50, 0x4b, 0x05, 0x06];
const DIRECTORY_ENTRY: [u8; 4] = [0x50, 0x4b, 0x01, 0x02];
const LOCAL_HEADER: [u8; 4] = [0x50, 0x4b, 0x03, 0x04];
const STORED: u16 = 0;
const DEFLATED: u16 = 8;

fn u16_at(data: &[u8], at: usize) -> Option<usize> {
    Some(usize::from(u16::from_le_bytes(
        data.get(at..at + 2)?.try_into().ok()?,
    )))
}

fn u32_at(data: &[u8], at: usize) -> Option<usize> {
    usize::try_from(u32::from_le_bytes(data.get(at..at + 4)?.try_into().ok()?)).ok()
}

/// The member's content, or None when the archive has no such member or can't be read.
pub(super) fn member(archive: &[u8], name: &str, limit: usize) -> Option<Zeroizing<Vec<u8>>> {
    // The end record is the last thing in the file, before an optional comment of at most 64 KiB.
    let floor = archive.len().saturating_sub(22 + 65_535);
    let end = (floor..=archive.len().checked_sub(22)?)
        .rev()
        .find(|&at| archive[at..at + 4] == END_OF_DIRECTORY)?;
    let count = u16_at(archive, end + 10)?;
    let mut at = u32_at(archive, end + 16)?;
    for _ in 0..count {
        if archive.get(at..at + 4)? != DIRECTORY_ENTRY {
            return None;
        }
        let flags = u16_at(archive, at + 8)?;
        let method = u16_at(archive, at + 10)?;
        let packed = u32_at(archive, at + 20)?;
        let size = u32_at(archive, at + 24)?;
        let name_length = u16_at(archive, at + 28)?;
        let extra_length = u16_at(archive, at + 30)?;
        let comment_length = u16_at(archive, at + 32)?;
        let local = u32_at(archive, at + 42)?;
        let member_name = archive.get(at + 46..at + 46 + name_length)?;
        at += 46 + name_length + extra_length + comment_length;
        if member_name != name.as_bytes() {
            continue;
        }
        let encrypted = flags & 1 == 1;
        if encrypted || size > limit || packed == 0xFFFF_FFFF || size == 0xFFFF_FFFF {
            return None;
        }
        if archive.get(local..local + 4)? != LOCAL_HEADER {
            return None;
        }
        let start = local + 30 + u16_at(archive, local + 26)? + u16_at(archive, local + 28)?;
        let body = archive.get(start..start.checked_add(packed)?)?;
        return match u16::try_from(method).ok()? {
            STORED => Some(Zeroizing::new(body.to_vec())),
            DEFLATED => {
                let mut out = Zeroizing::new(Vec::with_capacity(size.min(limit)));
                // One byte over the limit tells a too-large member from one that just fits.
                DeflateDecoder::new(body)
                    .take(limit as u64 + 1)
                    .read_to_end(&mut out)
                    .ok()?;
                (out.len() <= limit).then_some(out)
            }
            _ => None,
        };
    }
    None
}
