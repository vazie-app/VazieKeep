//! Reading other password managers' exports into entries a vault session can take in.
//!
//! An export is the person's passwords in plain text, so everything read from it is held in
//! zeroizing buffers and nothing from it is ever logged. Recognised: CSV with a header row (Chrome,
//! Firefox, `KeePassXC`, `LastPass` and the like), 1Password (`.1pux` and CSV), Bitwarden (JSON and
//! CSV). Encrypted exports are refused: there is nothing to read in them without their password.

mod bitwarden;
mod csv;
mod onepassword;
mod zip;

use std::fmt;

use zeroize::Zeroize;

use crate::kdbx::dom::is_xml_safe;
use crate::secret::SecretString;
use crate::totp::TotpConfig;

pub const MAX_IMPORT_SIZE: usize = 64 * 1024 * 1024;
pub const MAX_IMPORT_ENTRIES: usize = 50_000;
const STANDARD_KEYS: [&str; 5] = ["Title", "UserName", "Password", "URL", "Notes"];
use crate::totp::OTP_FIELD;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum ImportSource {
    Csv,
    OnePasswordCsv,
    OnePassword1pux,
    BitwardenJson,
    BitwardenCsv,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum ImportError {
    Empty,
    TooLarge,
    /// Not one of the formats this crate reads.
    Unrecognized,
    /// An encrypted or password-protected export.
    Encrypted,
    Malformed,
}

impl fmt::Display for ImportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Empty => "the file has nothing to import",
            Self::TooLarge => "the file is too large to import",
            Self::Unrecognized => "the file is not a known export format",
            Self::Encrypted => "the export is encrypted",
            Self::Malformed => "the export is damaged",
        })
    }
}

impl std::error::Error for ImportError {}

pub struct ImportedField {
    pub name: String,
    pub value: SecretString,
    pub protected: bool,
}

pub struct ImportedEntry {
    pub title: String,
    pub username: String,
    pub password: SecretString,
    pub url: String,
    pub notes: String,
    pub fields: Vec<ImportedField>,
    pub tags: Vec<String>,
    pub favorite: bool,
    /// Where the entry sat in the other app, outermost first.
    pub folder: Vec<String>,
}

impl fmt::Debug for ImportedField {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ImportedField")
            .field("protected", &self.protected)
            .finish_non_exhaustive()
    }
}

impl ImportedEntry {
    pub fn has_totp(&self) -> bool {
        self.fields.iter().any(|f| f.name == OTP_FIELD)
    }
}

impl Drop for ImportedEntry {
    fn drop(&mut self) {
        self.title.zeroize();
        self.username.zeroize();
        self.url.zeroize();
        self.notes.zeroize();
        self.tags.zeroize();
        self.folder.zeroize();
        for field in &mut self.fields {
            field.name.zeroize();
        }
    }
}

impl fmt::Debug for ImportedEntry {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ImportedEntry")
            .field("fields", &self.fields.len())
            .finish_non_exhaustive()
    }
}

pub struct ImportPlan {
    pub source: ImportSource,
    pub entries: Vec<ImportedEntry>,
    /// Records the export held that had nothing to keep (empty rows, deleted items).
    pub skipped: usize,
}

impl fmt::Debug for ImportPlan {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ImportPlan")
            .field("source", &self.source)
            .field("entries", &self.entries.len())
            .field("skipped", &self.skipped)
            .finish()
    }
}

/// Reads an export, working out which format it is from its content.
pub fn parse_import(data: &[u8]) -> Result<ImportPlan, ImportError> {
    if data.len() > MAX_IMPORT_SIZE {
        return Err(ImportError::TooLarge);
    }
    let start = data.iter().position(|b| !b.is_ascii_whitespace());
    let Some(start) = start else {
        return Err(ImportError::Empty);
    };
    let plan = if data.starts_with(b"PK\x03\x04") {
        onepassword::parse_1pux(data)?
    } else {
        let text = std::str::from_utf8(data).map_err(|_| ImportError::Unrecognized)?;
        let text = text.strip_prefix('\u{feff}').unwrap_or(text);
        if data[start] == b'{' || text.trim_start().starts_with('{') {
            bitwarden::parse_json(text)?
        } else {
            csv::parse(text)?
        }
    };
    if plan.entries.is_empty() {
        return Err(ImportError::Empty);
    }
    if plan.entries.len() > MAX_IMPORT_ENTRIES {
        return Err(ImportError::TooLarge);
    }
    Ok(plan)
}

/// Collects what a source knows about one record and turns it into an entry a vault accepts:
/// text a KDBX file can carry, unique field names, the one-time code where `KeePassXC` keeps it.
#[derive(Default)]
pub(crate) struct Builder {
    pub(crate) title: String,
    pub(crate) username: String,
    pub(crate) password: String,
    pub(crate) url: String,
    pub(crate) notes: String,
    pub(crate) totp: String,
    pub(crate) fields: Vec<(String, String, bool)>,
    pub(crate) tags: Vec<String>,
    pub(crate) favorite: bool,
    pub(crate) folder: Vec<String>,
}

impl Drop for Builder {
    fn drop(&mut self) {
        self.title.zeroize();
        self.username.zeroize();
        self.password.zeroize();
        self.url.zeroize();
        self.notes.zeroize();
        self.totp.zeroize();
        for (name, value, _) in &mut self.fields {
            name.zeroize();
            value.zeroize();
        }
    }
}

impl Builder {
    pub(crate) fn field(&mut self, name: &str, value: &str, protected: bool) {
        if !value.trim().is_empty() {
            self.fields
                .push((name.trim().to_owned(), value.to_owned(), protected));
        }
    }

    /// An extra address goes next to the first one rather than being lost.
    pub(crate) fn add_url(&mut self, url: &str) {
        let url = url.trim();
        if url.is_empty() {
            return;
        }
        if self.url.is_empty() {
            url.clone_into(&mut self.url);
        } else if self.url != url {
            let number = self
                .fields
                .iter()
                .filter(|f| f.0.starts_with("URL "))
                .count()
                + 2;
            self.fields
                .push((format!("URL {number}"), url.to_owned(), false));
        }
    }

    fn is_empty(&self) -> bool {
        [
            &self.title,
            &self.username,
            &self.password,
            &self.url,
            &self.notes,
            &self.totp,
        ]
        .iter()
        .all(|t| t.trim().is_empty())
            && self.fields.is_empty()
    }

    /// None when the record held nothing.
    pub(crate) fn finish(mut self) -> Option<ImportedEntry> {
        if self.is_empty() {
            return None;
        }
        let mut fields: Vec<ImportedField> = Vec::new();
        let totp = std::mem::take(&mut self.totp);
        if !totp.trim().is_empty() {
            match TotpConfig::parse(totp.trim()) {
                Ok(config) => fields.push(ImportedField {
                    name: OTP_FIELD.to_owned(),
                    value: config.to_uri(),
                    protected: true,
                }),
                // A code setup this app can't read is kept as it came, so it is not lost.
                Err(_) => self
                    .fields
                    .push(("TOTP".to_owned(), totp.trim().to_owned(), true)),
            }
        }
        let mut totp = totp;
        totp.zeroize();
        for (name, value, protected) in std::mem::take(&mut self.fields) {
            let clean = clean(&name);
            let base = if clean.trim().is_empty() {
                "Field".to_owned()
            } else {
                clean
            };
            let mut candidate = base.clone();
            let mut number = 2;
            while STANDARD_KEYS.contains(&candidate.as_str())
                || is_code_field(&candidate)
                || fields.iter().any(|f| f.name == candidate)
            {
                candidate = format!("{base} ({number})");
                number += 1;
            }
            fields.push(ImportedField {
                name: candidate,
                value: SecretString::new(clean_text(&value)),
                protected,
            });
            let mut value = value;
            value.zeroize();
        }
        let mut tags: Vec<String> = Vec::new();
        for tag in &self.tags {
            let tag = clean(tag).replace([';', ','], " ");
            let tag = tag.trim();
            if !tag.is_empty() && !tags.iter().any(|t| t == tag) {
                tags.push(tag.to_owned());
            }
        }
        Some(ImportedEntry {
            title: clean(&self.title).trim().to_owned(),
            username: clean(&self.username).trim().to_owned(),
            password: SecretString::new(clean(&self.password)),
            url: clean(&self.url).trim().to_owned(),
            notes: clean_text(&self.notes),
            fields,
            tags,
            favorite: self.favorite,
            folder: self
                .folder
                .iter()
                .map(|part| clean(part).trim().to_owned())
                .filter(|part| !part.is_empty())
                .collect(),
        })
    }
}

/// Names the app reads a one-time code from: an imported field must not land on one by accident.
fn is_code_field(name: &str) -> bool {
    name == OTP_FIELD
        || name.starts_with("TimeOtp-")
        || name == "TOTP Seed"
        || name == "TOTP Settings"
}

/// Drops what XML can't carry; a single-line value also loses its line breaks.
fn clean(text: &str) -> String {
    text.chars()
        .filter(|c| !matches!(c, '\n' | '\r') && is_xml_safe(c.encode_utf8(&mut [0; 4])))
        .collect()
}

fn clean_text(text: &str) -> String {
    text.chars()
        .filter(|c| is_xml_safe(c.encode_utf8(&mut [0; 4])))
        .collect::<String>()
        .replace("\r\n", "\n")
}

pub(crate) fn split_folder(path: &str) -> Vec<String> {
    path.split(['/', '\\'])
        .map(str::trim)
        .filter(|part| !part.is_empty())
        .map(str::to_owned)
        .collect()
}

pub(crate) fn is_true(value: &str) -> bool {
    matches!(
        value.trim().to_ascii_lowercase().as_str(),
        "1" | "true" | "yes" | "y"
    )
}
