use std::time::{SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;

const UNIX_TO_YEAR_ONE: i64 = 62_135_596_800;

pub(crate) fn now_unix() -> i64 {
    if let Some(seconds) = crate::clock::installed_unix() {
        return seconds;
    }
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_secs()).unwrap_or(i64::MAX))
}

/// KDBX 4 time: seconds since 0001-01-01 UTC as a little-endian i64, base64.
pub(crate) fn encode(unix: i64) -> String {
    STANDARD.encode(unix.saturating_add(UNIX_TO_YEAR_ONE).to_le_bytes())
}

pub(crate) fn now() -> String {
    encode(now_unix())
}

/// Unix seconds from a KDBX 4 time, or from the ISO 8601 text KDBX 3 and some writers use.
pub(crate) fn decode(text: &str) -> Option<i64> {
    let text = text.trim();
    if let Ok(bytes) = STANDARD.decode(text) {
        if let Ok(bytes) = <[u8; 8]>::try_from(bytes) {
            return Some(i64::from_le_bytes(bytes).saturating_sub(UNIX_TO_YEAR_ONE));
        }
    }
    decode_iso(text)
}

fn decode_iso(text: &str) -> Option<i64> {
    let text = text.strip_suffix('Z').unwrap_or(text);
    let (date, time) = text.split_once('T')?;
    let mut date = date.splitn(3, '-').map(str::parse::<i64>);
    let (year, month, day) = (date.next()?.ok()?, date.next()?.ok()?, date.next()?.ok()?);
    let time = time.split('.').next()?;
    let mut time = time.splitn(3, ':').map(str::parse::<i64>);
    let (hour, minute, second) = (time.next()?.ok()?, time.next()?.ok()?, time.next()?.ok()?);
    if !(1..=12).contains(&month)
        || !(1..=31).contains(&day)
        || !(0..24).contains(&hour)
        || !(0..60).contains(&minute)
        || !(0..=60).contains(&second)
    {
        return None;
    }
    Some(days_from_civil(year, month, day) * 86_400 + hour * 3600 + minute * 60 + second)
}

fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let year = if month <= 2 { year - 1 } else { year };
    let era = year.div_euclid(400);
    let year_of_era = year - era * 400;
    let month_index = (month + 9) % 12;
    let day_of_year = (153 * month_index + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - 719_468
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kdbx_time_is_seconds_since_year_one() {
        let bytes = STANDARD.decode(now()).unwrap();
        let seconds = i64::from_le_bytes(bytes.try_into().unwrap());
        assert!(seconds > 63_900_000_000 && seconds < 70_000_000_000);
    }

    #[test]
    fn round_trips_and_reads_iso_text() {
        assert_eq!(decode(&encode(1_791_244_800)), Some(1_791_244_800));
        assert_eq!(decode("2026-10-06T00:00:00Z"), Some(1_791_244_800));
        assert_eq!(decode("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(decode("2000-02-29T12:30:15.123Z"), Some(951_827_415));
        assert_eq!(decode("2026-13-01T00:00:00Z"), None);
        assert_eq!(decode("soon"), None);
    }
}
