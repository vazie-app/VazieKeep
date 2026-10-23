use keep_core::{
    Access, AccessState, ChangedField, Cipher, CustomField, CustomFieldInfo, EntryDetail,
    EntrySummary, GroupSummary, HistoryVersion, Kdf, Lease, Tier, VaultInfo, VisibleText,
};
use serde::{Deserialize, Serialize};

#[derive(Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub(crate) enum TextJs {
    Plain { value: String },
    Protected,
}

impl From<&VisibleText> for TextJs {
    fn from(text: &VisibleText) -> Self {
        match text {
            VisibleText::Plain(value) => Self::Plain {
                value: value.clone(),
            },
            VisibleText::Protected => Self::Protected,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct InfoJs {
    name: String,
    description: String,
    generator: String,
    format: String,
    cipher: &'static str,
    kdf: &'static str,
    root_group_id: String,
    recycle_bin_id: Option<String>,
    group_count: usize,
    entry_count: usize,
    fingerprint: String,
}

impl From<&VaultInfo> for InfoJs {
    fn from(info: &VaultInfo) -> Self {
        Self {
            name: info.name.clone(),
            description: info.description.clone(),
            generator: info.generator.clone(),
            format: format!("{}.{}", info.format.major, info.format.minor),
            cipher: match info.cipher {
                Cipher::Aes256 => "aes256",
                Cipher::ChaCha20 => "chacha20",
            },
            kdf: match info.kdf {
                Kdf::AesKdf => "aes-kdf",
                Kdf::Argon2d => "argon2d",
                Kdf::Argon2id => "argon2id",
            },
            root_group_id: info.root_group.to_string(),
            recycle_bin_id: info.recycle_bin.map(|id| id.to_string()),
            group_count: info.group_count,
            entry_count: info.entry_count,
            fingerprint: info.fingerprint.clone(),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct GroupJs {
    id: String,
    parent_id: Option<String>,
    name: String,
    icon: u32,
}

impl From<&GroupSummary> for GroupJs {
    fn from(group: &GroupSummary) -> Self {
        Self {
            id: group.id.to_string(),
            parent_id: group.parent.map(|id| id.to_string()),
            name: group.name.clone(),
            icon: group.icon,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CustomFieldInfoJs {
    name: String,
    protected: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PasskeyInfoJs {
    rp_id: String,
    user_name: String,
    credential_id: String,
    user_handle: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
#[allow(
    clippy::struct_excessive_bools,
    reason = "one flag per thing a row can show"
)]
pub(crate) struct SummaryJs {
    id: String,
    group_id: String,
    title: TextJs,
    username: TextJs,
    url: TextJs,
    tags: Vec<String>,
    favorite: bool,
    expires_at: Option<i64>,
    icon: u32,
    custom_icon: Option<String>,
    passkey: Option<PasskeyInfoJs>,
    has_password: bool,
    has_notes: bool,
    has_totp: bool,
    has_protected_fields: bool,
    custom_fields: Vec<CustomFieldInfoJs>,
    attachment_names: Vec<String>,
}

impl From<&EntrySummary> for SummaryJs {
    fn from(entry: &EntrySummary) -> Self {
        Self {
            id: entry.id.to_string(),
            group_id: entry.group.to_string(),
            title: (&entry.title).into(),
            username: (&entry.username).into(),
            url: (&entry.url).into(),
            tags: entry.tags.clone(),
            favorite: entry.favorite,
            expires_at: entry.expires,
            icon: entry.icon,
            custom_icon: entry.custom_icon.map(|id| id.to_string()),
            passkey: entry.passkey.as_ref().map(|p| PasskeyInfoJs {
                rp_id: p.rp_id.clone(),
                user_name: p.user_name.clone(),
                credential_id: p.credential_id.clone(),
                user_handle: p.user_handle.clone(),
            }),
            has_password: entry.has_password,
            has_notes: entry.has_notes,
            has_totp: entry.has_totp,
            has_protected_fields: entry.has_protected_fields(),
            custom_fields: entry
                .custom_fields
                .iter()
                .map(|CustomFieldInfo { name, protected }| CustomFieldInfoJs {
                    name: name.clone(),
                    protected: *protected,
                })
                .collect(),
            attachment_names: entry.attachment_names.clone(),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CustomFieldJs {
    name: String,
    value: TextJs,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AttachmentJs {
    name: String,
    size: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DetailJs {
    summary: SummaryJs,
    notes: TextJs,
    custom_fields: Vec<CustomFieldJs>,
    attachments: Vec<AttachmentJs>,
    modified_at: Option<i64>,
    history_count: usize,
}

impl From<&EntryDetail> for DetailJs {
    fn from(detail: &EntryDetail) -> Self {
        Self {
            summary: (&detail.summary).into(),
            notes: (&detail.notes).into(),
            custom_fields: detail
                .custom_fields
                .iter()
                .map(|CustomField { name, value }| CustomFieldJs {
                    name: name.clone(),
                    value: value.into(),
                })
                .collect(),
            attachments: detail
                .attachments
                .iter()
                .map(|a| AttachmentJs {
                    name: a.name.clone(),
                    size: a.size as u64,
                })
                .collect(),
            modified_at: detail.modified,
            history_count: detail.history_count,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct VersionJs {
    index: usize,
    modified_at: Option<i64>,
    title: TextJs,
    username: TextJs,
    differs: Vec<&'static str>,
}

impl From<&HistoryVersion> for VersionJs {
    fn from(version: &HistoryVersion) -> Self {
        Self {
            index: version.index,
            modified_at: version.modified,
            title: (&version.title).into(),
            username: (&version.username).into(),
            differs: version
                .differs
                .iter()
                .map(|field| match field {
                    ChangedField::Title => "title",
                    ChangedField::UserName => "userName",
                    ChangedField::Password => "password",
                    ChangedField::Url => "url",
                    ChangedField::Notes => "notes",
                    ChangedField::CustomFields => "customFields",
                    ChangedField::Tags => "tags",
                    ChangedField::Favorite => "favorite",
                    ChangedField::Attachments => "attachments",
                    ChangedField::Expiry => "expiry",
                })
                .collect(),
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CustomFieldDraftIn {
    pub(crate) name: String,
    pub(crate) value: String,
    pub(crate) protected: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct EntryDraftIn {
    pub(crate) title: String,
    pub(crate) username: String,
    pub(crate) password: String,
    pub(crate) url: String,
    pub(crate) notes: String,
    pub(crate) custom_fields: Vec<CustomFieldDraftIn>,
    pub(crate) tags: Vec<String>,
    pub(crate) favorite: bool,
    pub(crate) expires_at: Option<i64>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct AccessJs {
    state: String,
    plus_features: bool,
    needs_online_verification: bool,
    lease_valid_until: Option<i64>,
    observed_device_time: i64,
}

impl From<Access> for AccessJs {
    fn from(access: Access) -> Self {
        Self {
            state: match access.state {
                AccessState::Free => "free",
                AccessState::PlusActive => "plusActive",
                AccessState::PlusOfflineGrace => "plusOfflineGrace",
                AccessState::PlusLifetime => "plusLifetime",
                AccessState::PlusExpired => "plusExpired",
            }
            .to_owned(),
            plus_features: access.plus_features,
            needs_online_verification: access.needs_online_verification,
            lease_valid_until: access.lease_valid_until,
            observed_device_time: access.observed_device_time,
        }
    }
}

impl AccessJs {
    pub(crate) fn into_access(self) -> Access {
        Access {
            state: match self.state.as_str() {
                "plusActive" => AccessState::PlusActive,
                "plusOfflineGrace" => AccessState::PlusOfflineGrace,
                "plusLifetime" => AccessState::PlusLifetime,
                "plusExpired" => AccessState::PlusExpired,
                _ => AccessState::Free,
            },
            plus_features: self.plus_features,
            needs_online_verification: self.needs_online_verification,
            lease_valid_until: self.lease_valid_until,
            observed_device_time: self.observed_device_time,
        }
    }
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct LeaseJs {
    subject: String,
    tier: String,
    lifetime: bool,
    issued_at: i64,
    lease_expires_at: i64,
    entitlement_expires_at: Option<i64>,
    features_version: u32,
    revision: u64,
    nonce: String,
    key_id: String,
}

impl From<Lease> for LeaseJs {
    fn from(lease: Lease) -> Self {
        Self {
            subject: lease.subject,
            tier: match lease.tier {
                Tier::Free => "free",
                Tier::KeepPlus => "keepPlus",
            }
            .to_owned(),
            lifetime: lease.lifetime,
            issued_at: lease.issued_at,
            lease_expires_at: lease.lease_expires_at,
            entitlement_expires_at: lease.entitlement_expires_at,
            features_version: lease.features_version,
            revision: lease.revision,
            nonce: lease.nonce,
            key_id: lease.key_id,
        }
    }
}

impl LeaseJs {
    pub(crate) fn into_lease(self) -> Lease {
        Lease {
            subject: self.subject,
            tier: if self.tier == "keepPlus" {
                Tier::KeepPlus
            } else {
                Tier::Free
            },
            lifetime: self.lifetime,
            issued_at: self.issued_at,
            lease_expires_at: self.lease_expires_at,
            entitlement_expires_at: self.entitlement_expires_at,
            features_version: self.features_version,
            revision: self.revision,
            nonce: self.nonce,
            key_id: self.key_id,
        }
    }
}

#[derive(Deserialize)]
pub(crate) struct TrustedKeyIn {
    pub(crate) kid: String,
    #[serde(rename = "publicKey")]
    pub(crate) public_key: String,
}
