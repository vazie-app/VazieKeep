//! Bitwarden's unencrypted JSON export.

use serde::Deserialize;
use zeroize::Zeroize;

use super::{Builder, ImportError, ImportPlan, ImportSource, split_folder};

#[derive(Deserialize)]
struct Export {
    #[serde(default)]
    encrypted: bool,
    #[serde(default, rename = "passwordProtected")]
    password_protected: bool,
    #[serde(default)]
    folders: Vec<Folder>,
    items: Option<Vec<Item>>,
}

#[derive(Deserialize)]
struct Folder {
    id: Option<String>,
    name: Option<String>,
}

#[derive(Deserialize)]
struct Item {
    #[serde(default, rename = "type")]
    kind: u8,
    name: Option<String>,
    notes: Option<String>,
    #[serde(default)]
    favorite: bool,
    #[serde(rename = "folderId")]
    folder_id: Option<String>,
    #[serde(rename = "deletedDate")]
    deleted_date: Option<String>,
    login: Option<Login>,
    card: Option<serde_json::Map<String, serde_json::Value>>,
    identity: Option<serde_json::Map<String, serde_json::Value>>,
    #[serde(default)]
    fields: Option<Vec<Field>>,
}

#[derive(Deserialize)]
struct Login {
    username: Option<String>,
    password: Option<String>,
    totp: Option<String>,
    #[serde(default)]
    uris: Option<Vec<Uri>>,
}

#[derive(Deserialize)]
struct Uri {
    uri: Option<String>,
}

#[derive(Deserialize)]
struct Field {
    name: Option<String>,
    value: Option<String>,
    #[serde(default, rename = "type")]
    kind: u8,
}

const HIDDEN_FIELD: u8 = 1;
const LINKED_FIELD: u8 = 3;
/// Card and identity values that are secrets in themselves.
const PROTECTED: &[&str] = &["number", "code", "ssn", "passportNumber", "licenseNumber"];

pub(super) fn parse_json(text: &str) -> Result<ImportPlan, ImportError> {
    let export: Export = serde_json::from_str(text).map_err(|error| {
        if error.is_syntax() || error.is_eof() {
            ImportError::Malformed
        } else {
            ImportError::Unrecognized
        }
    })?;
    if export.encrypted || export.password_protected {
        return Err(ImportError::Encrypted);
    }
    let items = export.items.ok_or(ImportError::Unrecognized)?;
    let mut plan = ImportPlan {
        source: ImportSource::BitwardenJson,
        entries: Vec::new(),
        skipped: 0,
    };
    for item in items {
        if item.deleted_date.is_some() {
            plan.skipped += 1;
            continue;
        }
        let mut entry = Builder::default();
        entry.title = item.name.unwrap_or_default();
        entry.notes = item.notes.unwrap_or_default();
        entry.favorite = item.favorite;
        if let Some(id) = item.folder_id {
            let name = export
                .folders
                .iter()
                .find(|f| f.id.as_deref() == Some(id.as_str()))
                .and_then(|f| f.name.as_deref());
            entry.folder = split_folder(name.unwrap_or_default());
        }
        if let Some(mut login) = item.login {
            entry.username = login.username.take().unwrap_or_default();
            entry.password = login.password.take().unwrap_or_default();
            entry.totp = login.totp.take().unwrap_or_default();
            for uri in login.uris.take().unwrap_or_default() {
                entry.add_url(uri.uri.as_deref().unwrap_or_default());
            }
        }
        for object in [item.card, item.identity].into_iter().flatten() {
            for (name, value) in object {
                if let serde_json::Value::String(mut value) = value {
                    entry.field(&name, &value, PROTECTED.contains(&name.as_str()));
                    value.zeroize();
                }
            }
        }
        for field in item.fields.unwrap_or_default() {
            if field.kind == LINKED_FIELD {
                continue;
            }
            let mut value = field.value.unwrap_or_default();
            entry.field(
                field.name.as_deref().unwrap_or("Field"),
                &value,
                field.kind == HIDDEN_FIELD,
            );
            value.zeroize();
        }
        let _ = item.kind;
        match entry.finish() {
            Some(entry) => plan.entries.push(entry),
            None => plan.skipped += 1,
        }
    }
    Ok(plan)
}
