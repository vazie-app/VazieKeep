//! CSV with a header row. The columns are found by name, so the same reader takes the exports of
//! browsers, `KeePassXC`, `LastPass`, 1Password and Bitwarden.

use zeroize::Zeroizing;

use super::{Builder, ImportError, ImportPlan, ImportSource, is_true, split_folder};

type Row = Vec<Zeroizing<String>>;

const TITLE: &[&str] = &["title", "name", "account", "entry", "site name"];
const USERNAME: &[&str] = &[
    "username",
    "user name",
    "user",
    "login",
    "login name",
    "login_username",
    "email",
    "e-mail",
];
const PASSWORD: &[&str] = &["password", "pass", "login_password"];
const URL: &[&str] = &[
    "url",
    "urls",
    "website",
    "web site",
    "uri",
    "login_uri",
    "link",
    "web address",
];
const NOTES: &[&str] = &[
    "notes",
    "note",
    "comment",
    "comments",
    "extra",
    "description",
];
const TOTP: &[&str] = &[
    "totp",
    "otp",
    "otpauth",
    "login_totp",
    "2fa",
    "totp secret",
    "two factor secret",
];
const FOLDER: &[&str] = &["folder", "group", "grouping", "category", "path"];
const TAGS: &[&str] = &["tags", "tag", "labels"];
const FAVORITE: &[&str] = &["favorite", "favourite", "fav"];
const ARCHIVED: &[&str] = &["archived"];
const KIND: &[&str] = &["type"];
const BITWARDEN_FIELDS: &[&str] = &["fields"];
/// Bookkeeping columns of other apps that mean nothing in a vault.
const IGNORED: &[&str] = &[
    "icon",
    "last modified",
    "created",
    "modified",
    "timecreated",
    "timelastused",
    "timepasswordchanged",
    "httprealm",
    "formactionorigin",
    "guid",
    "reprompt",
    "id",
    "uuid",
];

pub(super) fn parse(text: &str) -> Result<ImportPlan, ImportError> {
    let delimiter = delimiter(text);
    let mut rows = read(text, delimiter)?.into_iter();
    let header: Vec<String> = rows
        .next()
        .ok_or(ImportError::Empty)?
        .iter()
        .map(|cell| cell.trim().to_lowercase())
        .collect();
    let column = |names: &[&str]| header.iter().position(|h| names.contains(&h.as_str()));
    let (title, username, password, url) = (
        column(TITLE),
        column(USERNAME),
        column(PASSWORD),
        column(URL),
    );
    // A table of something else has none of these; a real export has at least two.
    if password.is_none() || [title, username, url].iter().flatten().count() == 0 {
        return Err(ImportError::Unrecognized);
    }
    let (notes, totp, folder, tags) = (column(NOTES), column(TOTP), column(FOLDER), column(TAGS));
    let (favorite, archived, kind, extra) = (
        column(FAVORITE),
        column(ARCHIVED),
        column(KIND),
        column(BITWARDEN_FIELDS),
    );
    let bitwarden =
        header.iter().any(|h| h == "login_uri") && header.iter().any(|h| h == "login_username");
    let one_password = !bitwarden && header.iter().any(|h| h == "otpauth") && archived.is_some();
    let known: Vec<usize> = [
        title, username, password, url, notes, totp, folder, tags, favorite, archived, kind, extra,
    ]
    .into_iter()
    .flatten()
    .collect();

    let mut plan = ImportPlan {
        source: if bitwarden {
            ImportSource::BitwardenCsv
        } else if one_password {
            ImportSource::OnePasswordCsv
        } else {
            ImportSource::Csv
        },
        entries: Vec::new(),
        skipped: 0,
    };
    for row in rows {
        let cell = |index: Option<usize>| index.and_then(|i| row.get(i)).map_or("", |c| c.as_str());
        let mut entry = Builder::default();
        cell(title).clone_into(&mut entry.title);
        cell(username).clone_into(&mut entry.username);
        cell(password).clone_into(&mut entry.password);
        cell(notes).clone_into(&mut entry.notes);
        cell(totp).clone_into(&mut entry.totp);
        // Bitwarden lists several addresses in one cell.
        let addresses = cell(url);
        if bitwarden {
            for address in addresses.split(',') {
                entry.add_url(address);
            }
        } else {
            entry.add_url(addresses);
        }
        entry.folder = split_folder(cell(folder));
        // KeePassXC names the root group in every path.
        if entry.folder.first().is_some_and(|first| first == "Root") {
            entry.folder.remove(0);
        }
        if is_true(cell(archived)) {
            entry.folder.push("Archive".to_owned());
        }
        entry.tags = cell(tags).split([';', ',']).map(str::to_owned).collect();
        entry.favorite = is_true(cell(favorite));
        if bitwarden {
            // "name: value" per line.
            for line in cell(extra).lines() {
                if let Some((name, value)) = line.split_once(": ") {
                    entry.field(name, value, false);
                }
            }
        }
        for (index, name) in header.iter().enumerate() {
            if known.contains(&index) || IGNORED.contains(&name.as_str()) || name.is_empty() {
                continue;
            }
            if let Some(value) = row.get(index) {
                entry.field(name, value, false);
            }
        }
        match entry.finish() {
            Some(entry) => plan.entries.push(entry),
            None => plan.skipped += 1,
        }
    }
    Ok(plan)
}

/// The separator the header row uses most: a comma, or a semicolon or tab in some locales.
fn delimiter(text: &str) -> char {
    let header = text.lines().next().unwrap_or_default();
    [',', ';', '\t']
        .into_iter()
        .max_by_key(|d| (header.matches(*d).count(), *d == ','))
        .unwrap_or(',')
}

/// RFC 4180: quoted cells may hold the separator, line breaks and doubled quotes.
fn read(text: &str, delimiter: char) -> Result<Vec<Row>, ImportError> {
    let mut rows: Vec<Row> = Vec::new();
    let mut row: Row = Vec::new();
    let mut cell = Zeroizing::new(String::new());
    let mut quoted = false;
    let mut was_quoted = false;
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        if quoted {
            if c == '"' {
                if chars.peek() == Some(&'"') {
                    chars.next();
                    cell.push('"');
                } else {
                    quoted = false;
                }
            } else {
                cell.push(c);
            }
            continue;
        }
        match c {
            '"' if cell.is_empty() && !was_quoted => {
                quoted = true;
                was_quoted = true;
            }
            c if c == delimiter => {
                row.push(std::mem::take(&mut cell));
                was_quoted = false;
            }
            '\r' if chars.peek() == Some(&'\n') => {}
            '\n' | '\r' => {
                row.push(std::mem::take(&mut cell));
                was_quoted = false;
                if row.iter().any(|c| !c.is_empty()) {
                    rows.push(std::mem::take(&mut row));
                } else {
                    row.clear();
                }
            }
            c => cell.push(c),
        }
    }
    if quoted {
        return Err(ImportError::Malformed);
    }
    row.push(cell);
    if row.iter().any(|c| !c.is_empty()) {
        rows.push(row);
    }
    Ok(rows)
}
