//! 1Password's `.1pux` export: a ZIP whose `export.data` is JSON. Attached files are not imported.

use serde::Deserialize;
use serde_json::Value;
use zeroize::Zeroize;

use super::{Builder, ImportError, ImportPlan, ImportSource, MAX_IMPORT_SIZE, zip};

#[derive(Deserialize)]
struct Export {
    accounts: Vec<Account>,
}

#[derive(Deserialize)]
struct Account {
    #[serde(default)]
    vaults: Vec<Vault>,
}

#[derive(Deserialize)]
struct Vault {
    #[serde(default)]
    attrs: VaultAttributes,
    #[serde(default)]
    items: Vec<Item>,
}

#[derive(Deserialize, Default)]
struct VaultAttributes {
    #[serde(default)]
    name: String,
}

#[derive(Deserialize)]
struct Item {
    #[serde(default, rename = "favIndex")]
    favorite: i64,
    #[serde(default)]
    state: String,
    #[serde(default)]
    overview: Overview,
    #[serde(default)]
    details: Details,
}

#[derive(Deserialize, Default)]
struct Overview {
    #[serde(default)]
    title: String,
    #[serde(default)]
    url: String,
    #[serde(default)]
    urls: Vec<Address>,
    #[serde(default)]
    tags: Vec<String>,
}

#[derive(Deserialize)]
struct Address {
    #[serde(default)]
    url: String,
}

#[derive(Deserialize, Default)]
struct Details {
    #[serde(default, rename = "loginFields")]
    login_fields: Vec<LoginField>,
    #[serde(default, rename = "notesPlain")]
    notes: String,
    /// The value of a Password item, which has no login fields.
    #[serde(default)]
    password: String,
    #[serde(default)]
    sections: Vec<Section>,
}

#[derive(Deserialize)]
struct LoginField {
    #[serde(default)]
    value: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    designation: String,
}

#[derive(Deserialize)]
struct Section {
    #[serde(default)]
    title: String,
    #[serde(default)]
    fields: Vec<SectionField>,
}

#[derive(Deserialize)]
struct SectionField {
    #[serde(default)]
    title: String,
    #[serde(default)]
    value: serde_json::Map<String, Value>,
}

pub(super) fn parse_1pux(archive: &[u8]) -> Result<ImportPlan, ImportError> {
    let data =
        zip::member(archive, "export.data", MAX_IMPORT_SIZE).ok_or(ImportError::Unrecognized)?;
    let export: Export = serde_json::from_slice(&data).map_err(|_| ImportError::Malformed)?;
    let vaults: Vec<Vault> = export.accounts.into_iter().flat_map(|a| a.vaults).collect();
    let several = vaults.len() > 1;
    let mut plan = ImportPlan {
        source: ImportSource::OnePassword1pux,
        entries: Vec::new(),
        skipped: 0,
    };
    for vault in vaults {
        for item in vault.items {
            if item.state == "trashed" {
                plan.skipped += 1;
                continue;
            }
            let mut entry = Builder::default();
            entry.title = item.overview.title;
            entry.notes = item.details.notes;
            entry.password = item.details.password;
            entry.tags = item.overview.tags;
            entry.favorite = item.favorite != 0;
            if several && !vault.attrs.name.trim().is_empty() {
                entry.folder.push(vault.attrs.name.clone());
            }
            if item.state == "archived" {
                entry.folder.push("Archive".to_owned());
            }
            entry.add_url(&item.overview.url);
            for address in &item.overview.urls {
                entry.add_url(&address.url);
            }
            for mut field in item.details.login_fields {
                match field.designation.as_str() {
                    "username" if entry.username.is_empty() => {
                        entry.username = std::mem::take(&mut field.value);
                    }
                    "password" if entry.password.is_empty() => {
                        entry.password = std::mem::take(&mut field.value);
                    }
                    _ => {}
                }
                let _ = &field.name;
                field.value.zeroize();
            }
            for section in item.details.sections {
                for field in section.fields {
                    let name = match (section.title.trim(), field.title.trim()) {
                        ("", "") => "Field".to_owned(),
                        ("", title) => title.to_owned(),
                        (section, "") => section.to_owned(),
                        (section, title) => format!("{section}: {title}"),
                    };
                    for (kind, value) in field.value {
                        let Some(mut text) = text_of(&kind, &value) else {
                            continue;
                        };
                        if kind == "totp" && entry.totp.is_empty() {
                            entry.totp = std::mem::take(&mut text);
                        } else {
                            entry.field(
                                &name,
                                &text,
                                matches!(kind.as_str(), "concealed" | "creditCardNumber" | "totp"),
                            );
                        }
                        text.zeroize();
                    }
                }
            }
            match entry.finish() {
                Some(entry) => plan.entries.push(entry),
                None => plan.skipped += 1,
            }
        }
    }
    Ok(plan)
}

/// A section field's value as text. Files, references to other items and SSH keys are left out.
fn text_of(kind: &str, value: &Value) -> Option<String> {
    match (kind, value) {
        ("file" | "reference" | "sshKey", _) => None,
        (_, Value::String(text)) => Some(text.clone()),
        (_, Value::Number(number)) => Some(number.to_string()),
        (_, Value::Bool(flag)) => Some(flag.to_string()),
        ("address", Value::Object(parts)) => {
            let line: Vec<&str> = ["street", "city", "state", "zip", "country"]
                .iter()
                .filter_map(|key| parts.get(*key).and_then(Value::as_str))
                .filter(|part| !part.trim().is_empty())
                .collect();
            (!line.is_empty()).then(|| line.join(", "))
        }
        (_, Value::Object(parts)) => parts
            .get("emailAddress")
            .and_then(Value::as_str)
            .map(str::to_owned),
        _ => None,
    }
}
