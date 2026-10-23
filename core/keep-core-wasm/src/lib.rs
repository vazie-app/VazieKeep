#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss,
    reason = "JavaScript numbers are f64; counters and clocks stay far below 2^53"
)]
#![allow(
    clippy::fn_params_excessive_bools,
    reason = "one switch per character set, as in the UI"
)]

mod dto;

use std::collections::HashMap;
use std::sync::{LazyLock, Mutex, PoisonError};

use js_sys::{Array, JSON, Object, Reflect, Uint8Array};
use keep_core::{
    Argon2Settings, Cipher, CreateError, CreateVaultConfig, CustomFieldDraft, DeleteOutcome,
    EditError, EntryDraft, ErrorCategory, GeneratorError, HistoryError, HostMatch, ImportError,
    ImportPlan, ImportSource, ItemId, MergeError, MergeOptions, OpenError, PasskeyCreateError,
    PasskeyError, PasskeyRegistration, PasswordOptions, PlusFeature, SaveError, SecretBytes,
    SecretField, SecretString, SecurityPreset, SessionError, SessionId, TotpAlgorithm, TotpConfig,
    TotpEncoder, TotpError, TotpLookupError, TrustedKey, UnlockMaterial, VaultCredentials,
    VaultSession, VaultSessionStore,
};
use serde::Serialize;
use wasm_bindgen::prelude::{JsError, JsValue, wasm_bindgen};
use zeroize::{Zeroize, Zeroizing};

use dto::{
    AccessJs, DetailJs, EntryDraftIn, GroupJs, InfoJs, LeaseJs, SummaryJs, TrustedKeyIn, VersionJs,
};

static STORE: LazyLock<VaultSessionStore> = LazyLock::new(VaultSessionStore::default);
static PLANS: LazyLock<Mutex<(u32, HashMap<u32, ImportPlan>)>> =
    LazyLock::new(|| Mutex::new((0, HashMap::new())));

const MAX_IMPORT_PLANS: usize = 4;

/// A browser's WebAssembly has no clock of its own: the core is given the page's.
#[wasm_bindgen(start)]
pub fn start() {
    keep_core::set_clock(js_sys::Date::now);
}

#[wasm_bindgen(js_name = coreVersion)]
pub fn core_version() -> String {
    keep_core::core_info().version.to_owned()
}

// ---------------------------------------------------------------- opening and creating

/// The core's credentials from what the page hands over; `password` is wiped. With `material` (the
/// retained password component) the typed password is ignored.
fn credentials_from(
    password: &mut [u8],
    key_file: Option<Vec<u8>>,
    material: Option<Vec<u8>>,
) -> Result<VaultCredentials, JsError> {
    let key_file = key_file.map(SecretBytes::from);
    if let Some(material) = material {
        let material = SecretBytes::from(material);
        password.zeroize();
        VaultCredentials::from_unlock_material(
            &UnlockMaterial::decode(material.expose()).map_err(open_error)?,
            key_file,
        )
        .map_err(open_error)
    } else {
        let typed = (!password.is_empty() || key_file.is_none())
            .then(|| SecretBytes::from(password.to_vec()));
        password.zeroize();
        VaultCredentials::new(typed, key_file).map_err(open_error)
    }
}

/// Opens a database. `password` is wiped. With `material` (the retained password component) the
/// typed password is ignored. `retain_material` keeps the component in the session so it can be
/// exported once for a quick-unlock record.
#[wasm_bindgen(js_name = openVault)]
pub fn open_vault(
    data: &[u8],
    password: &mut [u8],
    key_file: Option<Vec<u8>>,
    material: Option<Vec<u8>>,
    retain_material: Option<bool>,
) -> Result<String, JsError> {
    let credentials = credentials_from(password, key_file, material)?;
    if !STORE.has_capacity() {
        return Err(code("too_many_sessions"));
    }
    let vault = keep_core::open_vault(data, &credentials).map_err(open_error)?;
    let mut session = VaultSession::new(vault).map_err(open_error)?;
    if retain_material.unwrap_or(false)
        && let Some(material) = credentials.unlock_material()
    {
        session.retain_unlock_material(material);
    }
    drop(credentials);
    let id = STORE.insert(session).map_err(session_error)?;
    Ok(id.to_string())
}

/// A new database. `password` is wiped. Returns `{ bytes, fingerprint }`.
#[wasm_bindgen(js_name = createVault)]
pub fn create_vault(
    name: &str,
    memory_kib: u32,
    iterations: u32,
    parallelism: u32,
    password: &mut [u8],
    key_file: Option<Vec<u8>>,
) -> Result<JsValue, JsError> {
    let key_file = key_file.map(SecretBytes::from);
    let typed = (!password.is_empty()).then(|| SecretBytes::from(password.to_vec()));
    password.zeroize();
    let credentials = VaultCredentials::new(typed, key_file).map_err(|error| match error {
        OpenError::InvalidKeyFile => code("invalid_key_file"),
        _ => code("missing_credentials"),
    })?;
    let config = CreateVaultConfig {
        name: name.to_owned(),
        kdf: Argon2Settings {
            memory_kib,
            iterations,
            parallelism,
        },
        cipher: Cipher::Aes256,
    };
    let created = keep_core::create_vault(&config, &credentials).map_err(create_error)?;
    Ok(object(&[
        ("bytes", Uint8Array::from(created.bytes.as_slice()).into()),
        ("fingerprint", created.fingerprint.into()),
    ]))
}

#[wasm_bindgen(js_name = securityPresets)]
pub fn security_presets() -> Result<JsValue, JsError> {
    #[derive(Serialize)]
    #[serde(rename_all = "camelCase")]
    struct Preset {
        memory_kib: u32,
        iterations: u32,
        parallelism: u32,
    }
    let of = |preset: SecurityPreset| {
        let settings = preset.settings();
        Preset {
            memory_kib: settings.memory_kib,
            iterations: settings.iterations,
            parallelism: settings.parallelism,
        }
    };
    to_js(&serde_json::json!({
        "balanced": of(SecurityPreset::Balanced),
        "stronger": of(SecurityPreset::Stronger),
    }))
}

/// Milliseconds one key derivation with these settings takes here.
#[wasm_bindgen(js_name = benchmarkKdf)]
pub fn benchmark_kdf(memory_kib: u32, iterations: u32, parallelism: u32) -> Result<u32, JsError> {
    keep_core::benchmark_kdf(Argon2Settings {
        memory_kib,
        iterations,
        parallelism,
    })
    .map_err(create_error)
}

#[wasm_bindgen(js_name = generateKeyFile)]
pub fn generate_key_file() -> Result<Uint8Array, JsError> {
    let key = keep_core::generate_key_file().map_err(|_| code("unavailable"))?;
    Ok(Uint8Array::from(key.expose()))
}

/// Whether the bytes look like a database this core can open: `kdbx`, `keepass1` or `unknown`.
#[wasm_bindgen(js_name = detectFormat)]
pub fn detect_format(header: &[u8]) -> String {
    match keep_core::detect_format(header) {
        keep_core::VaultFormat::Kdbx { major, .. } => format!("kdbx{major}"),
        keep_core::VaultFormat::KeePass1 => "keepass1".to_owned(),
        keep_core::VaultFormat::Unknown => "unknown".to_owned(),
    }
}

// ---------------------------------------------------------------- generator

#[wasm_bindgen(js_name = generatePassword)]
pub fn generate_password(
    length: u32,
    lowercase: bool,
    uppercase: bool,
    digits: bool,
    symbols: bool,
    exclude_ambiguous: bool,
) -> Result<Uint8Array, JsError> {
    let options = PasswordOptions {
        length: usize::try_from(length).map_err(|_| code("length_out_of_range"))?,
        lowercase,
        uppercase,
        digits,
        symbols,
        exclude_ambiguous,
    };
    let password = keep_core::generate_password(&options).map_err(|error| {
        code(match error {
            GeneratorError::NoCharacterSets => "no_character_sets",
            GeneratorError::RandomUnavailable => "unavailable",
            _ => "length_out_of_range",
        })
    })?;
    Ok(Uint8Array::from(password.expose().as_bytes()))
}

// ---------------------------------------------------------------- sessions: reading

#[wasm_bindgen(js_name = vaultInfo)]
pub fn vault_info(handle: &str) -> Result<JsValue, JsError> {
    to_js(&read(handle, |s| InfoJs::from(&s.info()))?)
}

#[wasm_bindgen(js_name = listGroups)]
pub fn list_groups(handle: &str) -> Result<JsValue, JsError> {
    to_js(&read(handle, |s| {
        s.groups().iter().map(GroupJs::from).collect::<Vec<_>>()
    })?)
}

#[wasm_bindgen(js_name = listEntries)]
pub fn list_entries(handle: &str) -> Result<JsValue, JsError> {
    to_js(&read(handle, |s| {
        s.entries().map(|e| SummaryJs::from(&e)).collect::<Vec<_>>()
    })?)
}

#[wasm_bindgen(js_name = searchEntries)]
pub fn search_entries(handle: &str, query: &str) -> Result<JsValue, JsError> {
    to_js(&read(handle, |s| {
        s.search(query)
            .iter()
            .map(SummaryJs::from)
            .collect::<Vec<_>>()
    })?)
}

#[wasm_bindgen(js_name = getEntry)]
pub fn get_entry(handle: &str, entry_id: &str) -> Result<JsValue, JsError> {
    let id = entry(entry_id)?;
    let detail = read(handle, |s| s.entry(id))?.map_err(session_error)?;
    to_js(&DetailJs::from(&detail))
}

#[wasm_bindgen(js_name = isEditable)]
pub fn is_editable(handle: &str) -> Result<bool, JsError> {
    read(handle, VaultSession::is_editable)
}

#[wasm_bindgen(js_name = isDirty)]
pub fn is_dirty(handle: &str) -> Result<bool, JsError> {
    read(handle, VaultSession::is_dirty)
}

#[wasm_bindgen(js_name = revealPassword)]
pub fn reveal_password(handle: &str, entry_id: &str) -> Result<Uint8Array, JsError> {
    reveal(handle, entry_id, SecretField::Password)
}

/// A field by its KDBX key: `Password`, `Title`, `UserName`, `URL`, `Notes` or a custom name.
#[wasm_bindgen(js_name = revealField)]
pub fn reveal_field(handle: &str, entry_id: &str, key: &str) -> Result<Uint8Array, JsError> {
    reveal(handle, entry_id, SecretField::from_kdbx_key(key))
}

fn reveal(handle: &str, entry_id: &str, field: SecretField<'_>) -> Result<Uint8Array, JsError> {
    let id = entry(entry_id)?;
    let secret: SecretString = read(handle, |s| s.reveal(id, field))?.map_err(session_error)?;
    Ok(Uint8Array::from(secret.expose().as_bytes()))
}

#[wasm_bindgen(js_name = attachmentBytes)]
pub fn attachment_bytes(handle: &str, entry_id: &str, name: &str) -> Result<Uint8Array, JsError> {
    let id = entry(entry_id)?;
    let bytes = read(handle, |s| s.attachment(id, name))?.map_err(session_error)?;
    Ok(Uint8Array::from(bytes.expose()))
}

#[wasm_bindgen(js_name = customIcon)]
pub fn custom_icon(handle: &str, icon_id: &str) -> Result<Option<Uint8Array>, JsError> {
    let Ok(id) = icon_id.parse::<ItemId>() else {
        return Ok(None);
    };
    Ok(read(handle, |s| s.custom_icon(id))?.map(|png| Uint8Array::from(png.as_slice())))
}

#[wasm_bindgen(js_name = unlockMaterial)]
pub fn unlock_material(handle: &str) -> Result<Option<Uint8Array>, JsError> {
    read(handle, |s| {
        s.unlock_material()
            .map(|material| Uint8Array::from(material.encode().expose()))
    })
}

// ---------------------------------------------------------------- history, codes

#[wasm_bindgen(js_name = historyVersions)]
pub fn history_versions(handle: &str, entry_id: &str, access: &str) -> Result<JsValue, JsError> {
    let (id, access) = (entry(entry_id)?, access_of(access)?);
    let versions = read(handle, |s| s.history(id, &access))?.map_err(history_error)?;
    to_js(&versions.iter().map(VersionJs::from).collect::<Vec<_>>())
}

#[wasm_bindgen(js_name = historyEntry)]
pub fn history_entry(
    handle: &str,
    entry_id: &str,
    index: u32,
    access: &str,
) -> Result<JsValue, JsError> {
    let (id, access) = (entry(entry_id)?, access_of(access)?);
    let detail =
        read(handle, |s| s.history_entry(id, index as usize, &access))?.map_err(history_error)?;
    to_js(&DetailJs::from(&detail))
}

#[wasm_bindgen(js_name = revealHistory)]
pub fn reveal_history(
    handle: &str,
    entry_id: &str,
    index: u32,
    key: &str,
    access: &str,
) -> Result<Uint8Array, JsError> {
    let (id, access) = (entry(entry_id)?, access_of(access)?);
    let secret = read(handle, |s| {
        s.reveal_history(id, index as usize, SecretField::from_kdbx_key(key), &access)
    })?
    .map_err(history_error)?;
    Ok(Uint8Array::from(secret.expose().as_bytes()))
}

#[wasm_bindgen(js_name = restoreHistory)]
pub fn restore_history(
    handle: &str,
    entry_id: &str,
    index: u32,
    access: &str,
) -> Result<(), JsError> {
    let (id, access) = (entry(entry_id)?, access_of(access)?);
    write(handle, |s| s.restore_history(id, index as usize, &access))?.map_err(edit_error)
}

/// `{ code, period, secondsRemaining }`.
#[wasm_bindgen(js_name = totpCode)]
pub fn totp_code(
    handle: &str,
    entry_id: &str,
    unix_seconds: f64,
    access: &str,
) -> Result<JsValue, JsError> {
    let (id, access) = (entry(entry_id)?, access_of(access)?);
    let now = unix_seconds.max(0.0) as u64;
    let made = read(handle, |s| s.totp(id, now, &access))?.map_err(totp_lookup_error)?;
    to_js(&serde_json::json!({
        "code": made.code.expose(),
        "period": made.period,
        "secondsRemaining": made.seconds_remaining,
    }))
}

/// Checks and normalises something a person pasted (an `otpauth://` link or a bare key).
/// `{ uri, algorithm, digits, period, steam, issuer, account }`.
#[wasm_bindgen(js_name = totpNormalize)]
pub fn totp_normalize(input: &mut [u8], access: &str) -> Result<JsValue, JsError> {
    let access = access_of(access)?;
    let bytes = Zeroizing::new(input.to_vec());
    input.zeroize();
    if !keep_core::can_use(PlusFeature::Totp, &access) {
        return Err(code("not_entitled"));
    }
    let text = std::str::from_utf8(&bytes).map_err(|_| code("invalid_text"))?;
    let config = TotpConfig::parse(text).map_err(totp_error)?;
    to_js(&serde_json::json!({
        "uri": config.to_uri().expose(),
        "algorithm": match config.algorithm() {
            TotpAlgorithm::Sha1 => "sha1",
            TotpAlgorithm::Sha256 => "sha256",
            TotpAlgorithm::Sha512 => "sha512",
        },
        "digits": config.digits(),
        "period": config.period(),
        "steam": config.encoder() == TotpEncoder::Steam,
        "issuer": config.issuer(),
        "account": config.account(),
    }))
}

// ---------------------------------------------------------------- editing

/// `draft` is the UTF-8 JSON of an entry draft; it is wiped.
#[wasm_bindgen(js_name = createEntry)]
pub fn create_entry(handle: &str, group_id: &str, draft: &mut [u8]) -> Result<String, JsError> {
    let group = edit_id(group_id)?;
    let draft = read_draft(draft)?;
    Ok(write(handle, |s| s.create_entry(group, &draft))?
        .map_err(edit_error)?
        .to_string())
}

#[wasm_bindgen(js_name = updateEntry)]
pub fn update_entry(handle: &str, entry_id: &str, draft: &mut [u8]) -> Result<(), JsError> {
    let id = edit_id(entry_id)?;
    let draft = read_draft(draft)?;
    write(handle, |s| s.update_entry(id, &draft))?.map_err(edit_error)
}

#[wasm_bindgen(js_name = setFavorite)]
pub fn set_favorite(handle: &str, entry_id: &str, favorite: bool) -> Result<(), JsError> {
    let id = edit_id(entry_id)?;
    write(handle, |s| s.set_favorite(id, favorite))?.map_err(edit_error)
}

/// `recycled` or `deleted`.
#[wasm_bindgen(js_name = deleteEntry)]
pub fn delete_entry(handle: &str, entry_id: &str, permanent: bool) -> Result<String, JsError> {
    let id = edit_id(entry_id)?;
    let outcome = write(handle, |s| s.delete_entry(id, permanent))?.map_err(edit_error)?;
    Ok(outcome_name(outcome))
}

#[wasm_bindgen(js_name = moveEntry)]
pub fn move_entry(handle: &str, entry_id: &str, group_id: &str) -> Result<(), JsError> {
    let (id, group) = (edit_id(entry_id)?, edit_id(group_id)?);
    write(handle, |s| s.move_entry(id, group))?.map_err(edit_error)
}

#[wasm_bindgen(js_name = createGroup)]
pub fn create_group(handle: &str, parent_id: &str, name: &str) -> Result<String, JsError> {
    let parent = edit_id(parent_id)?;
    Ok(write(handle, |s| s.create_group(parent, name))?
        .map_err(edit_error)?
        .to_string())
}

#[wasm_bindgen(js_name = renameGroup)]
pub fn rename_group(handle: &str, group_id: &str, name: &str) -> Result<(), JsError> {
    let id = edit_id(group_id)?;
    write(handle, |s| s.rename_group(id, name))?.map_err(edit_error)
}

#[wasm_bindgen(js_name = deleteGroup)]
pub fn delete_group(
    handle: &str,
    group_id: &str,
    recursive: bool,
    permanent: bool,
) -> Result<String, JsError> {
    let id = edit_id(group_id)?;
    let outcome =
        write(handle, |s| s.delete_group(id, recursive, permanent))?.map_err(edit_error)?;
    Ok(outcome_name(outcome))
}

#[wasm_bindgen(js_name = setEntryIcon)]
pub fn set_entry_icon(handle: &str, entry_id: &str, icon: u32) -> Result<(), JsError> {
    let id = edit_id(entry_id)?;
    write(handle, |s| s.set_entry_icon(id, icon))?.map_err(edit_error)
}

#[wasm_bindgen(js_name = setGroupIcon)]
pub fn set_group_icon(handle: &str, group_id: &str, icon: u32) -> Result<(), JsError> {
    let id = edit_id(group_id)?;
    write(handle, |s| s.set_group_icon(id, icon))?.map_err(edit_error)
}

#[wasm_bindgen(js_name = assignIconsByUrl)]
pub fn assign_icons_by_url(handle: &str) -> Result<u32, JsError> {
    let changed = write(handle, VaultSession::assign_icons_by_url)?.map_err(edit_error)?;
    Ok(u32::try_from(changed).unwrap_or(u32::MAX))
}

#[wasm_bindgen(js_name = siteIconHosts)]
pub fn site_icon_hosts(handle: &str) -> Result<JsValue, JsError> {
    to_js(&read(handle, VaultSession::site_icon_hosts)?)
}

#[wasm_bindgen(js_name = siteIconHost)]
pub fn site_icon_host(handle: &str, entry_id: &str) -> Result<Option<String>, JsError> {
    let Ok(id) = entry_id.parse::<ItemId>() else {
        return Ok(None);
    };
    read(handle, |s| s.site_icon_host(id))
}

/// `icons` is an array of `{ host: string, png: Uint8Array }`. Returns how many entries got one.
#[wasm_bindgen(js_name = setSiteIcons)]
pub fn set_site_icons(handle: &str, icons: &JsValue) -> Result<u32, JsError> {
    let list = Array::from(icons);
    let mut parsed: Vec<(String, Vec<u8>)> = Vec::with_capacity(list.length() as usize);
    for item in list.iter() {
        let host = Reflect::get(&item, &"host".into())
            .ok()
            .and_then(|v| v.as_string())
            .ok_or_else(|| code("invalid_argument"))?;
        let png = Reflect::get(&item, &"png".into()).map_err(|_| code("invalid_argument"))?;
        parsed.push((host, Uint8Array::new(&png).to_vec()));
    }
    let changed = write(handle, |s| s.set_site_icons(&parsed))?.map_err(edit_error)?;
    Ok(u32::try_from(changed).unwrap_or(u32::MAX))
}

#[wasm_bindgen(js_name = addAttachment)]
pub fn add_attachment(
    handle: &str,
    entry_id: &str,
    name: &str,
    data: &mut [u8],
    access: &str,
) -> Result<String, JsError> {
    let (id, access) = (edit_id(entry_id)?, access_of(access)?);
    let bytes = SecretBytes::from(data.to_vec());
    data.zeroize();
    write(handle, |s| {
        s.add_attachment(id, name, bytes.expose(), &access)
    })?
    .map_err(edit_error)
}

#[wasm_bindgen(js_name = removeAttachment)]
pub fn remove_attachment(handle: &str, entry_id: &str, name: &str) -> Result<(), JsError> {
    let id = edit_id(entry_id)?;
    write(handle, |s| s.remove_attachment(id, name))?.map_err(edit_error)
}

// ---------------------------------------------------------------- import

/// Reads an export. `data` is wiped. Returns `{ planId, summary }`.
#[wasm_bindgen(js_name = parseImport)]
pub fn parse_import(data: &mut [u8]) -> Result<JsValue, JsError> {
    let bytes = SecretBytes::from(data.to_vec());
    data.zeroize();
    let plan = keep_core::parse_import(bytes.expose()).map_err(|error| {
        code(match error {
            ImportError::Empty => "empty",
            ImportError::TooLarge => "too_large",
            ImportError::Encrypted => "encrypted",
            ImportError::Malformed => "malformed",
            _ => "unrecognized",
        })
    })?;
    let mut folders: Vec<&[String]> = plan
        .entries
        .iter()
        .filter(|e| !e.folder.is_empty())
        .map(|e| e.folder.as_slice())
        .collect();
    folders.sort_unstable();
    folders.dedup();
    let summary = serde_json::json!({
        "source": match plan.source {
            ImportSource::OnePasswordCsv => "onePasswordCsv",
            ImportSource::OnePassword1pux => "onePassword1pux",
            ImportSource::BitwardenJson => "bitwardenJson",
            ImportSource::BitwardenCsv => "bitwardenCsv",
            _ => "csv",
        },
        "entries": plan.entries.len(),
        "folders": folders.len(),
        "withTotp": plan.entries.iter().filter(|e| e.has_totp()).count(),
        "skipped": plan.skipped,
    });
    let mut plans = PLANS.lock().unwrap_or_else(PoisonError::into_inner);
    if plans.1.len() >= MAX_IMPORT_PLANS {
        plans.1.clear();
    }
    plans.0 = plans.0.wrapping_add(1);
    let id = plans.0;
    plans.1.insert(id, plan);
    Ok(object(&[
        ("planId", id.into()),
        ("summary", to_js(&summary)?),
    ]))
}

#[wasm_bindgen(js_name = dropImport)]
pub fn drop_import(plan_id: u32) {
    PLANS
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .1
        .remove(&plan_id);
}

/// Takes every entry of a parsed export into `group_id`. `{ entries, groups }`.
#[wasm_bindgen(js_name = importPlan)]
pub fn import_plan(handle: &str, group_id: &str, plan_id: u32) -> Result<JsValue, JsError> {
    let group = edit_id(group_id)?;
    let plans = PLANS.lock().unwrap_or_else(PoisonError::into_inner);
    let plan = plans
        .1
        .get(&plan_id)
        .ok_or_else(|| code("invalid_argument"))?;
    let outcome = write(handle, |s| s.import(group, plan))?.map_err(edit_error)?;
    to_js(&serde_json::json!({ "entries": outcome.entries, "groups": outcome.groups }))
}

/// Combines another version of this database (its encrypted bytes, opened with its own credentials,
/// wiped as for `openVault`) into the open one. Nothing is written: the session is dirty if anything
/// changed. The other file takes no session. Returns the report: counts and the entry ids of the
/// conflict copies, never a value from the vault.
#[wasm_bindgen(js_name = mergeVault)]
pub fn merge_vault(
    handle: &str,
    data: &[u8],
    password: &mut [u8],
    key_file: Option<Vec<u8>>,
    material: Option<Vec<u8>>,
    conflict_suffix: &str,
    allow_unrelated: bool,
) -> Result<JsValue, JsError> {
    let credentials = credentials_from(password, key_file, material)?;
    let other = keep_core::open_vault(data, &credentials).map_err(open_error)?;
    drop(credentials);
    let options = MergeOptions {
        conflict_suffix: conflict_suffix.to_owned(),
        allow_unrelated,
    };
    let report = write(handle, |s| s.merge(other, &options))?.map_err(merge_error)?;
    to_js(&serde_json::json!({
        "changed": report.changed(),
        "entriesAdded": report.entries_added,
        "entriesRestored": report.entries_restored,
        "entriesUpdated": report.entries_updated,
        "entriesMoved": report.entries_moved,
        "entriesDeleted": report.entries_deleted,
        "historiesMerged": report.histories_merged,
        "historyVersionsAdded": report.history_versions_added,
        "groupsAdded": report.groups_added,
        "groupsUpdated": report.groups_updated,
        "groupsMoved": report.groups_moved,
        "groupsDeleted": report.groups_deleted,
        "conflicts": report.conflicts.iter().map(ToString::to_string).collect::<Vec<_>>(),
        "iconsAdded": report.icons_added,
        "deletionRecordsAdded": report.deletion_records_added,
        "settingsUpdated": report.settings_updated,
    }))
}

// ---------------------------------------------------------------- passkeys

/// `{ entryId, credentialId, publicKey, authenticatorData, attestationObject }`.
#[wasm_bindgen(js_name = createPasskey)]
pub fn create_passkey(
    handle: &str,
    group_id: &str,
    rp_id: &str,
    rp_name: &str,
    user_name: &str,
    user_handle: &[u8],
) -> Result<JsValue, JsError> {
    let group = group_id
        .parse::<ItemId>()
        .map_err(|_| code("invalid_request"))?;
    let request = PasskeyRegistration {
        rp_id,
        rp_name,
        user_name,
        user_handle,
    };
    let made =
        write(handle, |s| s.create_passkey(group, &request))?.map_err(passkey_create_error)?;
    Ok(object(&[
        ("entryId", made.entry.to_string().into()),
        (
            "credentialId",
            Uint8Array::from(made.credential_id.as_slice()).into(),
        ),
        (
            "publicKey",
            Uint8Array::from(made.public_key.as_slice()).into(),
        ),
        (
            "authenticatorData",
            Uint8Array::from(made.authenticator_data.as_slice()).into(),
        ),
        (
            "attestationObject",
            Uint8Array::from(made.attestation_object.as_slice()).into(),
        ),
    ]))
}

/// The entries that hold a passkey for a site; every passkey entry when `rp_id` is empty.
#[wasm_bindgen(js_name = listPasskeys)]
pub fn list_passkeys(handle: &str, rp_id: &str) -> Result<JsValue, JsError> {
    let wanted = (!rp_id.is_empty()).then_some(rp_id);
    to_js(&read(handle, |s| {
        s.passkeys(wanted)
            .iter()
            .map(|(summary, _)| SummaryJs::from(summary))
            .collect::<Vec<_>>()
    })?)
}

/// `{ credentialId, userHandle, authenticatorData, signature }`.
#[wasm_bindgen(js_name = signPasskey)]
pub fn sign_passkey(
    handle: &str,
    entry_id: &str,
    rp_id: &str,
    client_data_hash: &[u8],
) -> Result<JsValue, JsError> {
    let id = entry_id.parse::<ItemId>().map_err(|_| code("no_passkey"))?;
    let signed =
        read(handle, |s| s.sign_passkey(id, rp_id, client_data_hash))?.map_err(passkey_error)?;
    Ok(object(&[
        (
            "credentialId",
            Uint8Array::from(signed.credential_id.as_slice()).into(),
        ),
        (
            "userHandle",
            Uint8Array::from(signed.user_handle.as_slice()).into(),
        ),
        (
            "authenticatorData",
            Uint8Array::from(signed.authenticator_data.as_slice()).into(),
        ),
        (
            "signature",
            Uint8Array::from(signed.signature.as_slice()).into(),
        ),
    ]))
}

#[wasm_bindgen(js_name = removePasskey)]
pub fn remove_passkey(handle: &str, entry_id: &str) -> Result<(), JsError> {
    let id = edit_id(entry_id)?;
    write(handle, |s| s.remove_passkey(id))?.map_err(edit_error)
}

// ---------------------------------------------------------------- saving

/// `{ bytes, generation }`: encrypted, verified bytes for the host to write.
#[wasm_bindgen(js_name = prepareSave)]
pub fn prepare_save(handle: &str) -> Result<JsValue, JsError> {
    let snapshot = read(handle, VaultSession::prepare_save)?.map_err(|error| {
        code(match error {
            SaveError::ReadOnly => "read_only",
            SaveError::RandomUnavailable => "random_unavailable",
            SaveError::VerificationFailed => "verification_failed",
            _ => "encryption_failed",
        })
    })?;
    Ok(object(&[
        ("bytes", Uint8Array::from(snapshot.bytes.as_slice()).into()),
        ("generation", (snapshot.generation as f64).into()),
    ]))
}

/// Why the last `prepareSave` failed its read-back check, without any secret value.
#[wasm_bindgen(js_name = lastSaveDiagnostic)]
pub fn last_save_diagnostic() -> Option<String> {
    keep_core::last_save_diagnostic()
}

#[wasm_bindgen(js_name = markSaved)]
pub fn mark_saved(handle: &str, generation: f64) -> Result<bool, JsError> {
    write(handle, |s| s.mark_saved(generation.max(0.0) as u64))
}

#[wasm_bindgen(js_name = closeVault)]
pub fn close_vault(handle: &str) -> bool {
    SessionId::parse(handle).is_ok_and(|id| STORE.close(id))
}

#[wasm_bindgen(js_name = closeAllVaults)]
pub fn close_all_vaults() -> u32 {
    PLANS
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .1
        .clear();
    u32::try_from(STORE.close_all()).unwrap_or(u32::MAX)
}

// ---------------------------------------------------------------- sites and icons

#[wasm_bindgen(js_name = matchHost)]
pub fn match_host(entry_host: &str, request_host: &str) -> String {
    match keep_core::match_host(entry_host, request_host) {
        HostMatch::Exact => "exact",
        HostMatch::Subdomain => "subdomain",
        HostMatch::None => "none",
    }
    .to_owned()
}

#[wasm_bindgen(js_name = siteHost)]
pub fn site_host(url: &str) -> Option<String> {
    keep_core::site_host(url)
}

#[wasm_bindgen(js_name = iconForUrl)]
pub fn icon_for_url(url: &str) -> Option<u32> {
    keep_core::icon_for_url(url)
}

#[wasm_bindgen(js_name = siteIconLinks)]
pub fn site_icon_links(host: &str, html: &str) -> Result<JsValue, JsError> {
    to_js(&keep_core::icon_links(host, html))
}

// ---------------------------------------------------------------- Keep+ lease

/// `keys` is a JSON array of `{ kid, publicKey }`.
#[wasm_bindgen(js_name = verifyKeepLease)]
pub fn verify_keep_lease(token: &str, keys: &str) -> Result<JsValue, JsError> {
    let keys: Vec<TrustedKeyIn> =
        serde_json::from_str(keys).map_err(|_| code("invalid_argument"))?;
    let keys: Vec<TrustedKey> = keys
        .iter()
        .filter_map(|key| TrustedKey::from_base64url(&key.kid, &key.public_key).ok())
        .collect();
    let lease = keep_core::verify_lease(token, &keys).map_err(|error| {
        code(match error {
            keep_core::LeaseError::Malformed => "malformed",
            keep_core::LeaseError::UnsupportedAlgorithm => "unsupported_algorithm",
            keep_core::LeaseError::UnknownKey => "unknown_key",
            keep_core::LeaseError::BadSignature => "bad_signature",
            keep_core::LeaseError::Unsupported => "unsupported",
            keep_core::LeaseError::Inconsistent => "inconsistent",
        })
    })?;
    to_js(&LeaseJs::from(lease))
}

/// `lease` is the JSON of a verified lease or `null`.
#[wasm_bindgen(js_name = evaluateKeepAccess)]
pub fn evaluate_keep_access(lease: &str, now: f64, last_observed: f64) -> Result<JsValue, JsError> {
    let lease: Option<LeaseJs> =
        serde_json::from_str(lease).map_err(|_| code("invalid_argument"))?;
    let lease = lease.map(LeaseJs::into_lease);
    let access = keep_core::evaluate(lease.as_ref(), now as i64, last_observed as i64);
    to_js(&AccessJs::from(access))
}

#[wasm_bindgen(js_name = canUsePlusFeature)]
pub fn can_use_plus_feature(feature: &str, access: &str) -> Result<bool, JsError> {
    let feature = match feature {
        "multiVault" => PlusFeature::MultiVault,
        "totp" => PlusFeature::Totp,
        "passwordHealth" => PlusFeature::PasswordHealth,
        "breachMonitoring" => PlusFeature::BreachMonitoring,
        "historyUi" => PlusFeature::HistoryUi,
        "attachmentWrite" => PlusFeature::AttachmentWrite,
        "premiumThemes" => PlusFeature::PremiumThemes,
        _ => return Err(code("invalid_argument")),
    };
    Ok(keep_core::can_use(feature, &access_of(access)?))
}

// ---------------------------------------------------------------- helpers

fn read<R>(handle: &str, body: impl FnOnce(&VaultSession) -> R) -> Result<R, JsError> {
    let id = SessionId::parse(handle).map_err(session_error)?;
    STORE.read(id, body).map_err(session_error)
}

fn write<R>(handle: &str, body: impl FnOnce(&mut VaultSession) -> R) -> Result<R, JsError> {
    let id = SessionId::parse(handle).map_err(session_error)?;
    STORE.write(id, body).map_err(session_error)
}

fn entry(entry_id: &str) -> Result<ItemId, JsError> {
    entry_id.parse().map_err(|_| code("invalid_argument"))
}

fn edit_id(text: &str) -> Result<ItemId, JsError> {
    text.parse().map_err(|_| code("invalid_argument"))
}

fn access_of(json: &str) -> Result<keep_core::Access, JsError> {
    serde_json::from_str::<AccessJs>(json)
        .map(AccessJs::into_access)
        .map_err(|_| code("invalid_argument"))
}

fn read_draft(bytes: &mut [u8]) -> Result<EntryDraft, JsError> {
    let parsed = serde_json::from_slice::<EntryDraftIn>(bytes);
    bytes.zeroize();
    let draft = parsed.map_err(|_| code("invalid_text"))?;
    Ok(EntryDraft {
        title: draft.title,
        username: draft.username,
        password: SecretString::new(draft.password),
        url: draft.url,
        notes: draft.notes,
        custom_fields: draft
            .custom_fields
            .into_iter()
            .map(|f| CustomFieldDraft {
                name: f.name,
                value: SecretString::new(f.value),
                protected: f.protected,
            })
            .collect(),
        tags: draft.tags,
        favorite: draft.favorite,
        expires: draft.expires_at,
    })
}

fn outcome_name(outcome: DeleteOutcome) -> String {
    match outcome {
        DeleteOutcome::MovedToRecycleBin => "recycled",
        DeleteOutcome::Deleted => "deleted",
    }
    .to_owned()
}

fn to_js<T: Serialize>(value: &T) -> Result<JsValue, JsError> {
    let json = Zeroizing::new(serde_json::to_string(value).map_err(|_| code("unavailable"))?);
    JSON::parse(&json).map_err(|_| code("unavailable"))
}

fn object(pairs: &[(&str, JsValue)]) -> JsValue {
    let object = Object::new();
    for (key, value) in pairs {
        let _ = Reflect::set(&object, &JsValue::from_str(key), value);
    }
    object.into()
}

fn code(name: &str) -> JsError {
    JsError::new(name)
}

fn open_error(error: OpenError) -> JsError {
    code(match error.category() {
        ErrorCategory::NotAVault => "not_a_vault",
        ErrorCategory::Unsupported => "unsupported",
        ErrorCategory::MissingCredentials => "missing_credentials",
        ErrorCategory::InvalidCredentials => "invalid_credentials",
        ErrorCategory::InvalidKeyFile => "invalid_key_file",
        ErrorCategory::Damaged => "damaged",
        ErrorCategory::TooLarge => "too_large",
    })
}

fn session_error(error: SessionError) -> JsError {
    code(match error {
        SessionError::InvalidHandle => "invalid_argument",
        SessionError::SessionClosed => "session_closed",
        SessionError::EntryNotFound => "entry_not_found",
        SessionError::FieldNotFound => "field_not_found",
        SessionError::TooManySessions => "too_many_sessions",
        SessionError::RandomUnavailable => "unavailable",
        _ => "session_not_found",
    })
}

fn edit_error(error: EditError) -> JsError {
    code(match error {
        EditError::ReadOnly => "read_only",
        EditError::EntryNotFound => "entry_not_found",
        EditError::GroupNotFound => "group_not_found",
        EditError::GroupNotEmpty => "group_not_empty",
        EditError::RootGroup => "root_group",
        EditError::InvalidFieldName => "invalid_field_name",
        EditError::RandomUnavailable => "random_unavailable",
        EditError::AttachmentNotFound => "attachment_not_found",
        EditError::AttachmentTooLarge => "attachment_too_large",
        EditError::NotEntitled => "not_entitled",
        EditError::VersionNotFound => "version_not_found",
        EditError::InvalidIcon => "invalid_argument",
        _ => "invalid_text",
    })
}

fn merge_error(error: MergeError) -> JsError {
    code(match error {
        MergeError::ReadOnly => "read_only",
        MergeError::DifferentVault => "different_vault",
        MergeError::InvalidVault => "invalid_result",
        _ => "unavailable",
    })
}

fn history_error(error: HistoryError) -> JsError {
    match error {
        HistoryError::Session(error) => session_error(error),
        HistoryError::NotEntitled => code("not_entitled"),
        _ => code("version_not_found"),
    }
}

fn totp_error(error: TotpError) -> JsError {
    code(match error {
        TotpError::Empty => "empty",
        TotpError::TooLong => "too_long",
        TotpError::UnsupportedType => "unsupported_type",
        TotpError::MissingSecret => "missing_secret",
        TotpError::InvalidSecret => "invalid_secret",
        TotpError::InvalidAlgorithm => "invalid_algorithm",
        TotpError::InvalidDigits => "invalid_digits",
        TotpError::InvalidPeriod => "invalid_period",
        TotpError::Migration => "migration",
        _ => "malformed",
    })
}

fn totp_lookup_error(error: TotpLookupError) -> JsError {
    match error {
        TotpLookupError::Session(error) => session_error(error),
        TotpLookupError::NotEntitled => code("not_entitled"),
        TotpLookupError::NoCode => code("no_code"),
        TotpLookupError::Invalid(error) => totp_error(error),
        _ => code("malformed"),
    }
}

fn passkey_error(error: PasskeyError) -> JsError {
    code(match error {
        PasskeyError::InvalidRequest => "invalid_request",
        PasskeyError::NoPasskey => "no_passkey",
        PasskeyError::InvalidKey => "invalid_key",
        _ => "failed",
    })
}

fn passkey_create_error(error: PasskeyCreateError) -> JsError {
    match error {
        PasskeyCreateError::Passkey(inner) => passkey_error(inner),
        PasskeyCreateError::Edit(EditError::ReadOnly) => code("read_only"),
        PasskeyCreateError::Edit(EditError::GroupNotFound) => code("group_not_found"),
        PasskeyCreateError::Edit(_) => code("failed"),
    }
}

fn create_error(error: CreateError) -> JsError {
    code(match error {
        CreateError::EmptyName => "empty_name",
        CreateError::NameTooLong | CreateError::InvalidName => "invalid_name",
        CreateError::InvalidKdf => "invalid_settings",
        CreateError::MissingCredentials => "missing_credentials",
        CreateError::RandomUnavailable
        | CreateError::EncryptionFailed
        | CreateError::VerificationFailed => "unavailable",
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_comes_from_core() {
        assert_eq!(core_version(), keep_core::core_info().version);
    }

    #[test]
    fn closing_malformed_or_unknown_handles_is_false() {
        assert!(!close_vault("not a handle"));
        assert!(!close_vault("0123456789abcdef0123456789abcdef"));
    }

    #[test]
    fn hosts_match_by_the_shared_policy() {
        assert_eq!(match_host("example.com", "example.com"), "exact");
        assert_eq!(
            match_host("example.com", "accounts.example.com"),
            "subdomain"
        );
        assert_eq!(match_host("example.com", "paypaI.com"), "none");
        assert_eq!(match_host("co.uk", "evil.co.uk"), "none");
    }
}
