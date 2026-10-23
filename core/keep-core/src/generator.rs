//! Password generation from the system CSPRNG. Each character is drawn uniformly (rejection
//! sampling, no modulo bias); every selected set appears at least once; the result is shuffled.

use zeroize::Zeroizing;

use crate::secret::SecretString;

const LOWER: &str = "abcdefghijklmnopqrstuvwxyz";
const UPPER: &str = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const DIGITS: &str = "0123456789";
const SYMBOLS: &str = "!#$%&()*+,-./:;<=>?@[]^_{}~";
/// Characters easily confused when read or typed.
const AMBIGUOUS: &str = "0O1lI|5S2Z8B";

pub const MIN_LENGTH: usize = 4;
pub const MAX_LENGTH: usize = 128;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(
    clippy::struct_excessive_bools,
    reason = "one switch per character set, as in the UI"
)]
pub struct PasswordOptions {
    pub length: usize,
    pub lowercase: bool,
    pub uppercase: bool,
    pub digits: bool,
    pub symbols: bool,
    pub exclude_ambiguous: bool,
}

impl Default for PasswordOptions {
    fn default() -> Self {
        Self {
            length: 20,
            lowercase: true,
            uppercase: true,
            digits: true,
            symbols: true,
            exclude_ambiguous: false,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum GeneratorError {
    NoCharacterSets,
    LengthOutOfRange,
    RandomUnavailable,
}

impl std::fmt::Display for GeneratorError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::NoCharacterSets => "no character set selected",
            Self::LengthOutOfRange => "length out of range",
            Self::RandomUnavailable => "system random source unavailable",
        })
    }
}

impl std::error::Error for GeneratorError {}

pub fn generate_password(options: &PasswordOptions) -> Result<SecretString, GeneratorError> {
    let sets: Vec<Vec<char>> = [
        (options.lowercase, LOWER),
        (options.uppercase, UPPER),
        (options.digits, DIGITS),
        (options.symbols, SYMBOLS),
    ]
    .into_iter()
    .filter(|(on, _)| *on)
    .map(|(_, set)| {
        set.chars()
            .filter(|c| !options.exclude_ambiguous || !AMBIGUOUS.contains(*c))
            .collect()
    })
    .collect();
    if sets.is_empty() {
        return Err(GeneratorError::NoCharacterSets);
    }
    if !(MIN_LENGTH..=MAX_LENGTH).contains(&options.length) || options.length < sets.len() {
        return Err(GeneratorError::LengthOutOfRange);
    }
    let all: Vec<char> = sets.iter().flatten().copied().collect();
    let mut chars: Zeroizing<Vec<char>> = Zeroizing::new(Vec::with_capacity(options.length));
    for set in &sets {
        chars.push(set[uniform(set.len())?]);
    }
    while chars.len() < options.length {
        chars.push(all[uniform(all.len())?]);
    }
    for i in (1..chars.len()).rev() {
        let j = uniform(i + 1)?;
        chars.swap(i, j);
    }
    Ok(SecretString::new(chars.iter().collect()))
}

/// A uniform index in `0..bound` (bound ≤ 2^32).
fn uniform(bound: usize) -> Result<usize, GeneratorError> {
    let bound = u32::try_from(bound).map_err(|_| GeneratorError::LengthOutOfRange)?;
    let zone = u32::MAX - u32::MAX % bound;
    loop {
        let mut bytes = [0u8; 4];
        getrandom::fill(&mut bytes).map_err(|_| GeneratorError::RandomUnavailable)?;
        let value = u32::from_le_bytes(bytes);
        if value < zone {
            return usize::try_from(value % bound).map_err(|_| GeneratorError::LengthOutOfRange);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_length_and_every_selected_set() {
        for length in [4, 5, 20, 64, 128] {
            let password = generate_password(&PasswordOptions {
                length,
                ..PasswordOptions::default()
            })
            .unwrap();
            let text = password.expose();
            assert_eq!(text.chars().count(), length);
            assert!(text.chars().any(|c| c.is_ascii_lowercase()));
            assert!(text.chars().any(|c| c.is_ascii_uppercase()));
            assert!(text.chars().any(|c| c.is_ascii_digit()));
            assert!(text.chars().any(|c| SYMBOLS.contains(c)));
        }
    }

    #[test]
    fn only_selected_sets_and_no_ambiguous_characters() {
        let options = PasswordOptions {
            length: 128,
            lowercase: false,
            uppercase: true,
            digits: true,
            symbols: false,
            exclude_ambiguous: true,
        };
        for _ in 0..50 {
            let password = generate_password(&options).unwrap();
            assert!(
                password
                    .expose()
                    .chars()
                    .all(|c| (c.is_ascii_uppercase() || c.is_ascii_digit())
                        && !AMBIGUOUS.contains(c))
            );
        }
    }

    #[test]
    fn invalid_options_are_errors_not_empty_passwords() {
        let none = PasswordOptions {
            lowercase: false,
            uppercase: false,
            digits: false,
            symbols: false,
            ..PasswordOptions::default()
        };
        assert_eq!(
            generate_password(&none).unwrap_err(),
            GeneratorError::NoCharacterSets
        );
        assert_eq!(
            generate_password(&PasswordOptions {
                length: 3,
                ..PasswordOptions::default()
            })
            .unwrap_err(),
            GeneratorError::LengthOutOfRange
        );
        assert_eq!(
            generate_password(&PasswordOptions {
                length: 129,
                ..PasswordOptions::default()
            })
            .unwrap_err(),
            GeneratorError::LengthOutOfRange
        );
    }

    #[test]
    #[allow(clippy::cast_precision_loss, reason = "small test counts")]
    fn randomness_sanity() {
        let options = PasswordOptions {
            length: 32,
            ..PasswordOptions::default()
        };
        let samples: Vec<String> = (0..200)
            .map(|_| generate_password(&options).unwrap().expose().to_owned())
            .collect();
        let mut unique = samples.clone();
        unique.sort();
        unique.dedup();
        assert_eq!(unique.len(), samples.len());
        // Every character of a 4-set alphabet should show up across 6400 draws.
        let alphabet: usize = [LOWER, UPPER, DIGITS, SYMBOLS]
            .iter()
            .map(|s| s.len())
            .sum();
        let mut seen: Vec<char> = samples.iter().flat_map(|s| s.chars()).collect();
        seen.sort_unstable();
        seen.dedup();
        assert_eq!(seen.len(), alphabet);
        // Digits are 10 of 89 characters: expect about 11 % of the draws (with the guaranteed one).
        let digits = samples
            .iter()
            .flat_map(|s| s.chars())
            .filter(char::is_ascii_digit)
            .count();
        let share = digits as f64 / (samples.len() * 32) as f64;
        assert!((0.08..0.16).contains(&share), "{share}");
    }

    #[test]
    fn uniform_has_no_bias_at_the_edges() {
        let mut counts = [0usize; 3];
        for _ in 0..3000 {
            counts[uniform(3).unwrap()] += 1;
        }
        assert!(
            counts.iter().all(|&c| (850..1150).contains(&c)),
            "{counts:?}"
        );
    }
}
