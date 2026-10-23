//! Which of the standard `KeePass` icons suits an entry, judged from its address alone. Nothing is
//! fetched and nothing leaves the device: the address is compared with a short list of well-known
//! services and a few words that say what a site is for.

/// Standard `KeePass` icon numbers the rules hand out.
mod icon {
    pub(super) const WEB: u32 = 1;
    pub(super) const SERVER: u32 = 3;
    pub(super) const CHAT: u32 = 5;
    pub(super) const MEDIA: u32 = 11;
    pub(super) const MAIL: u32 = 19;
    pub(super) const TERMINAL: u32 = 30;
    pub(super) const ARCHIVE: u32 = 36;
    pub(super) const BANK: u32 = 37;
    pub(super) const SHOPPING: u32 = 47;
    pub(super) const BOOK: u32 = 56;
    pub(super) const WORK: u32 = 59;
    pub(super) const MONEY: u32 = 66;
    pub(super) const CERTIFICATE: u32 = 67;
    pub(super) const PHONE: u32 = 68;
}

/// A site by its domain: the host is that domain or one of its subdomains. The more specific
/// address comes first (`aws.amazon.com` before `amazon.com`).
const DOMAINS: &[(&str, u32)] = &[
    ("mail.google.com", icon::MAIL),
    ("gmail.com", icon::MAIL),
    ("mail.yandex.ru", icon::MAIL),
    ("mail.yandex.com", icon::MAIL),
    ("mail.ru", icon::MAIL),
    ("outlook.com", icon::MAIL),
    ("outlook.live.com", icon::MAIL),
    ("proton.me", icon::MAIL),
    ("protonmail.com", icon::MAIL),
    ("fastmail.com", icon::MAIL),
    ("tuta.com", icon::MAIL),
    ("tutanota.com", icon::MAIL),
    ("mail.yahoo.com", icon::MAIL),
    ("aws.amazon.com", icon::SERVER),
    ("console.aws.amazon.com", icon::SERVER),
    ("cloud.google.com", icon::SERVER),
    ("console.cloud.google.com", icon::SERVER),
    ("azure.com", icon::SERVER),
    ("portal.azure.com", icon::SERVER),
    ("digitalocean.com", icon::SERVER),
    ("hetzner.com", icon::SERVER),
    ("hetzner.cloud", icon::SERVER),
    ("cloudflare.com", icon::SERVER),
    ("ovh.com", icon::SERVER),
    ("linode.com", icon::SERVER),
    ("vultr.com", icon::SERVER),
    ("fornex.com", icon::SERVER),
    ("selectel.ru", icon::SERVER),
    ("timeweb.cloud", icon::SERVER),
    ("reg.ru", icon::SERVER),
    ("namecheap.com", icon::SERVER),
    ("godaddy.com", icon::SERVER),
    ("cloud.yandex.ru", icon::SERVER),
    ("drive.google.com", icon::ARCHIVE),
    ("disk.yandex.ru", icon::ARCHIVE),
    ("dropbox.com", icon::ARCHIVE),
    ("onedrive.live.com", icon::ARCHIVE),
    ("icloud.com", icon::ARCHIVE),
    ("mega.nz", icon::ARCHIVE),
    ("box.com", icon::ARCHIVE),
    ("market.yandex.ru", icon::SHOPPING),
    ("amazon.com", icon::SHOPPING),
    ("amazon.de", icon::SHOPPING),
    ("amazon.co.uk", icon::SHOPPING),
    ("ebay.com", icon::SHOPPING),
    ("aliexpress.com", icon::SHOPPING),
    ("aliexpress.ru", icon::SHOPPING),
    ("ozon.ru", icon::SHOPPING),
    ("wildberries.ru", icon::SHOPPING),
    ("avito.ru", icon::SHOPPING),
    ("etsy.com", icon::SHOPPING),
    ("walmart.com", icon::SHOPPING),
    ("lamoda.ru", icon::SHOPPING),
    ("ikea.com", icon::SHOPPING),
    ("dns-shop.ru", icon::SHOPPING),
    ("music.yandex.ru", icon::MEDIA),
    ("music.apple.com", icon::MEDIA),
    ("youtube.com", icon::MEDIA),
    ("netflix.com", icon::MEDIA),
    ("twitch.tv", icon::MEDIA),
    ("spotify.com", icon::MEDIA),
    ("kinopoisk.ru", icon::MEDIA),
    ("vimeo.com", icon::MEDIA),
    ("soundcloud.com", icon::MEDIA),
    ("ivi.ru", icon::MEDIA),
    ("rutube.ru", icon::MEDIA),
    ("disneyplus.com", icon::MEDIA),
    ("sberbank.ru", icon::BANK),
    ("sber.ru", icon::BANK),
    ("online.sberbank.ru", icon::BANK),
    ("tbank.ru", icon::BANK),
    ("tinkoff.ru", icon::BANK),
    ("alfabank.ru", icon::BANK),
    ("vtb.ru", icon::BANK),
    ("gazprombank.ru", icon::BANK),
    ("raiffeisen.ru", icon::BANK),
    ("chase.com", icon::BANK),
    ("wellsfargo.com", icon::BANK),
    ("hsbc.com", icon::BANK),
    ("barclays.co.uk", icon::BANK),
    ("revolut.com", icon::BANK),
    ("monzo.com", icon::BANK),
    ("n26.com", icon::BANK),
    ("citi.com", icon::BANK),
    ("paypal.com", icon::MONEY),
    ("stripe.com", icon::MONEY),
    ("wise.com", icon::MONEY),
    ("payoneer.com", icon::MONEY),
    ("qiwi.com", icon::MONEY),
    ("yoomoney.ru", icon::MONEY),
    ("robokassa.ru", icon::MONEY),
    ("robokassa.com", icon::MONEY),
    ("binance.com", icon::MONEY),
    ("coinbase.com", icon::MONEY),
    ("kraken.com", icon::MONEY),
    ("facebook.com", icon::CHAT),
    ("instagram.com", icon::CHAT),
    ("twitter.com", icon::CHAT),
    ("x.com", icon::CHAT),
    ("vk.com", icon::CHAT),
    ("vk.ru", icon::CHAT),
    ("ok.ru", icon::CHAT),
    ("telegram.org", icon::CHAT),
    ("t.me", icon::CHAT),
    ("whatsapp.com", icon::CHAT),
    ("discord.com", icon::CHAT),
    ("slack.com", icon::CHAT),
    ("reddit.com", icon::CHAT),
    ("tiktok.com", icon::CHAT),
    ("threads.net", icon::CHAT),
    ("snapchat.com", icon::CHAT),
    ("pinterest.com", icon::CHAT),
    ("tumblr.com", icon::CHAT),
    ("signal.org", icon::CHAT),
    ("mastodon.social", icon::CHAT),
    ("github.com", icon::TERMINAL),
    ("gitlab.com", icon::TERMINAL),
    ("bitbucket.org", icon::TERMINAL),
    ("stackoverflow.com", icon::TERMINAL),
    ("npmjs.com", icon::TERMINAL),
    ("docker.com", icon::TERMINAL),
    ("pypi.org", icon::TERMINAL),
    ("crates.io", icon::TERMINAL),
    ("jetbrains.com", icon::TERMINAL),
    ("vercel.com", icon::TERMINAL),
    ("netlify.com", icon::TERMINAL),
    ("heroku.com", icon::TERMINAL),
    ("developer.apple.com", icon::TERMINAL),
    ("play.google.com", icon::PHONE),
    ("hh.ru", icon::WORK),
    ("linkedin.com", icon::WORK),
    ("indeed.com", icon::WORK),
    ("upwork.com", icon::WORK),
    ("notion.so", icon::WORK),
    ("atlassian.com", icon::WORK),
    ("atlassian.net", icon::WORK),
    ("trello.com", icon::WORK),
    ("asana.com", icon::WORK),
    ("zoom.us", icon::WORK),
    ("office.com", icon::WORK),
    ("figma.com", icon::WORK),
    ("coursera.org", icon::BOOK),
    ("udemy.com", icon::BOOK),
    ("edx.org", icon::BOOK),
    ("duolingo.com", icon::BOOK),
    ("khanacademy.org", icon::BOOK),
    ("wikipedia.org", icon::BOOK),
    ("stepik.org", icon::BOOK),
    ("gosuslugi.ru", icon::CERTIFICATE),
    ("nalog.ru", icon::CERTIFICATE),
    ("nalog.gov.ru", icon::CERTIFICATE),
    ("mos.ru", icon::CERTIFICATE),
    ("mts.ru", icon::PHONE),
    ("beeline.ru", icon::PHONE),
    ("megafon.ru", icon::PHONE),
    ("tele2.ru", icon::PHONE),
    ("t2.ru", icon::PHONE),
    ("t-mobile.com", icon::PHONE),
    ("verizon.com", icon::PHONE),
    ("vodafone.com", icon::PHONE),
    ("att.com", icon::PHONE),
];

/// A word inside the host that says what the site is for, when the domain itself is not known.
const WORDS: &[(&str, u32)] = &[
    ("webmail", icon::MAIL),
    ("mail", icon::MAIL),
    ("bank", icon::BANK),
    ("pay", icon::MONEY),
    ("wallet", icon::MONEY),
    ("shop", icon::SHOPPING),
    ("store", icon::SHOPPING),
    ("market", icon::SHOPPING),
    ("git", icon::TERMINAL),
    ("cloud", icon::SERVER),
    ("hosting", icon::SERVER),
    ("forum", icon::CHAT),
];

/// The host of an address, lower-cased and without `www.`; None when there is none to read.
fn host(url: &str) -> Option<String> {
    let url = url.trim();
    let rest = url.split_once("://").map_or(url, |(_, rest)| rest);
    let authority = rest.split(['/', '?', '#']).next()?;
    let host = authority.rsplit('@').next()?;
    let host = host
        .split(':')
        .next()?
        .trim()
        .trim_end_matches('.')
        .to_lowercase();
    let host = host.strip_prefix("www.").unwrap_or(&host).to_owned();
    (host.contains('.') && !host.contains(' ')).then_some(host)
}

/// The host a site icon is fetched from, when the address is one of a website.
pub fn site_host(url: &str) -> Option<String> {
    let lower = url.trim().to_lowercase();
    if lower.contains("://") && !lower.starts_with("http://") && !lower.starts_with("https://") {
        return None;
    }
    let host = to_ascii(&host(url)?)?;
    let last = host.rsplit('.').next()?;
    let named = last.starts_with("xn--")
        || (last.len() >= 2 && last.chars().all(|c| c.is_ascii_alphabetic()));
    let safe = host
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-');
    (named && safe).then_some(host)
}

/// A host as it goes on the wire: a label with letters outside ASCII (`пример.рф`) becomes its
/// `xn--` form. Only lower-casing is applied before encoding, which is what such names need.
fn to_ascii(host: &str) -> Option<String> {
    let mut labels = Vec::new();
    for label in host.split('.') {
        if label.is_ascii() {
            labels.push(label.to_owned());
        } else {
            labels.push(format!("xn--{}", punycode(&label.to_lowercase())?));
        }
    }
    Some(labels.join("."))
}

/// RFC 3492, encoding only.
fn punycode(label: &str) -> Option<String> {
    const BASE: u32 = 36;
    const T_MIN: u32 = 1;
    const T_MAX: u32 = 26;
    const DIGITS: &[u8; 36] = b"abcdefghijklmnopqrstuvwxyz0123456789";
    let digit = |d: u32| char::from(DIGITS[d as usize % DIGITS.len()]);
    let adapt = |mut delta: u32, points: u32, first: bool| {
        delta /= if first { 700 } else { 2 };
        delta += delta / points;
        let mut k = 0;
        while delta > ((BASE - T_MIN) * T_MAX) / 2 {
            delta /= BASE - T_MIN;
            k += BASE;
        }
        k + (BASE - T_MIN + 1) * delta / (delta + 38)
    };
    let input: Vec<u32> = label.chars().map(u32::from).collect();
    let mut output: String = label.chars().filter(char::is_ascii).collect();
    let basic = u32::try_from(output.len()).ok()?;
    if basic > 0 {
        output.push('-');
    }
    let (mut n, mut delta, mut bias, mut handled) = (128u32, 0u32, 72u32, basic);
    let total = u32::try_from(input.len()).ok()?;
    while handled < total {
        let next = input.iter().copied().filter(|c| *c >= n).min()?;
        delta = delta.checked_add((next - n).checked_mul(handled + 1)?)?;
        n = next;
        for c in &input {
            if *c < n {
                delta = delta.checked_add(1)?;
            }
            if *c == n {
                let mut q = delta;
                let mut k = BASE;
                loop {
                    let t = if k <= bias {
                        T_MIN
                    } else if k >= bias + T_MAX {
                        T_MAX
                    } else {
                        k - bias
                    };
                    if q < t {
                        break;
                    }
                    output.push(digit(t + (q - t) % (BASE - t)));
                    q = (q - t) / (BASE - t);
                    k += BASE;
                }
                output.push(digit(q));
                bias = adapt(delta, handled + 1, handled == basic);
                delta = 0;
                handled += 1;
            }
        }
        delta = delta.checked_add(1)?;
        n = n.checked_add(1)?;
    }
    Some(output)
}

/// Where a site's icon may be, best first: what the page itself names in its `link` tags (the
/// large touch icon before the small ones), then the usual `/favicon.ico`. Only https addresses
/// are kept, and vector images are left out.
pub fn icon_links(host: &str, html: &str) -> Vec<String> {
    let mut found: Vec<(u32, String)> = Vec::new();
    let lower = html.to_ascii_lowercase();
    let mut from = 0;
    while let Some(start) = lower[from..].find("<link") {
        let begin = from + start;
        let Some(length) = lower[begin..].find('>') else {
            break;
        };
        let tag = &html[begin..begin + length];
        from = begin + length;
        let Some(rel) = attribute(tag, "rel") else {
            continue;
        };
        let rel = rel.to_ascii_lowercase();
        if !rel
            .split_ascii_whitespace()
            .any(|word| word.contains("icon"))
            || rel.contains("mask")
        {
            continue;
        }
        let Some(link) = attribute(tag, "href").and_then(|href| resolve(host, &href)) else {
            continue;
        };
        let size = attribute(tag, "sizes")
            .and_then(|sizes| sizes.split(['x', 'X']).next()?.trim().parse::<u32>().ok())
            .unwrap_or(if rel.contains("apple") { 180 } else { 16 });
        // An icon near the size the apps draw is best; a huge one is only a slower download.
        let rank = if size >= 64 {
            size.min(1024)
        } else {
            2048 - size
        };
        if !found.iter().any(|(_, known)| *known == link) {
            found.push((rank, link));
        }
    }
    found.sort_by_key(|(rank, _)| *rank);
    let mut links: Vec<String> = found.into_iter().map(|(_, link)| link).collect();
    let fallback = format!("https://{host}/favicon.ico");
    if !links.contains(&fallback) {
        links.push(fallback);
    }
    links.truncate(MAX_ICON_LINKS);
    links
}

const MAX_ICON_LINKS: usize = 4;

fn attribute(tag: &str, name: &str) -> Option<String> {
    let lower = tag.to_ascii_lowercase();
    let mut from = 0;
    loop {
        let at = from + lower[from..].find(name)?;
        from = at + name.len();
        let before = lower[..at].chars().next_back()?;
        if !before.is_ascii_whitespace() && before != '"' && before != '\'' {
            continue;
        }
        let rest = tag[from..].trim_start();
        let Some(rest) = rest.strip_prefix('=') else {
            continue;
        };
        let rest = rest.trim_start();
        let value = match rest.chars().next()? {
            quote @ ('"' | '\'') => rest[1..].split(quote).next()?,
            _ => rest
                .split(|c: char| c.is_ascii_whitespace() || c == '>')
                .next()?,
        };
        return Some(value.trim().replace("&amp;", "&"));
    }
}

fn resolve(host: &str, href: &str) -> Option<String> {
    let href = href.trim();
    let link = if let Some(rest) = href.strip_prefix("//") {
        format!("https://{rest}")
    } else if href.starts_with("https://") {
        href.to_owned()
    } else if href.contains(':') {
        return None;
    } else if let Some(path) = href.strip_prefix('/') {
        format!("https://{host}/{path}")
    } else {
        format!("https://{host}/{}", href.trim_start_matches("./"))
    };
    let path = link.split(['?', '#']).next().unwrap_or(&link);
    let vector = path.to_ascii_lowercase().ends_with(".svg");
    let plain = link.len() <= 512 && link.chars().all(|c| c.is_ascii_graphic());
    (plain && !vector).then_some(link)
}

/// The icon for an entry with this address, or None when the address says nothing (no host at all).
/// An address this crate does not recognise gets the globe.
#[must_use]
pub fn icon_for_url(url: &str) -> Option<u32> {
    let lower = url.trim().to_lowercase();
    if lower.starts_with("androidapp://")
        || lower.starts_with("android://")
        || lower.starts_with("ios://")
    {
        return Some(icon::PHONE);
    }
    if lower.starts_with("ssh://") || lower.starts_with("sftp://") || lower.starts_with("ftp://") {
        return Some(icon::SERVER);
    }
    let host = host(url)?;
    let within = |domain: &str| {
        host == domain
            || host
                .strip_suffix(domain)
                .is_some_and(|rest| rest.ends_with('.'))
    };
    if let Some((_, icon)) = DOMAINS
        .iter()
        .filter(|(domain, _)| within(domain))
        .max_by_key(|(domain, _)| domain.len())
    {
        return Some(*icon);
    }
    let labels: Vec<&str> = host.split('.').collect();
    if labels.contains(&"gov") {
        return Some(icon::CERTIFICATE);
    }
    let academic = labels.len() >= 3 && labels[labels.len() - 2] == "ac";
    if labels.contains(&"edu") || academic {
        return Some(icon::BOOK);
    }
    // The last label is the top-level domain and says nothing about the site.
    let named = &labels[..labels.len().saturating_sub(1)];
    for (word, icon) in WORDS {
        if named.iter().any(|label| label.contains(word)) {
            return Some(*icon);
        }
    }
    Some(icon::WEB)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_website_has_a_host_to_ask() {
        assert_eq!(
            site_host("https://www.Example.com:8443/login?x=1"),
            Some("example.com".to_owned())
        );
        assert_eq!(
            site_host("mail.example.org"),
            Some("mail.example.org".to_owned())
        );
        assert_eq!(site_host("androidapp://com.example"), None);
        assert_eq!(site_host("ssh://host.example.com"), None);
        assert_eq!(site_host("https://192.168.1.1/admin"), None);
        assert_eq!(site_host("router"), None);
        assert_eq!(site_host("https://exa mple.com"), None);
        assert_eq!(
            site_host("https://Президент.рф/news"),
            Some("xn--d1abbgf6aiiy.xn--p1ai".to_owned())
        );
        assert_eq!(
            site_host("bücher.example"),
            Some("xn--bcher-kva.example".to_owned())
        );
    }

    #[test]
    fn the_page_says_where_its_icon_is() {
        let html = r#"<html><head>
            <LINK REL="shortcut icon" HREF="/static/fav.ico">
            <link rel='icon' type="image/png" sizes="32x32" href=fav-32.png>
            <link rel="icon" sizes="192x192" href="https://cdn.example.net/i/192.png?v=1&amp;x=2">
            <link rel="apple-touch-icon" href="//cdn.example.net/touch.png">
            <link rel="mask-icon" href="/mask.svg">
            <link rel="icon" href="/logo.svg">
            <link rel="icon" href="data:image/png;base64,AAAA">
            <link rel="icon" href="http://example.com/plain.png">
            <link rel="stylesheet" href="/icons.css">
        </head>"#;
        assert_eq!(
            icon_links("example.com", html),
            [
                "https://cdn.example.net/touch.png",
                "https://cdn.example.net/i/192.png?v=1&x=2",
                "https://example.com/fav-32.png",
                "https://example.com/static/fav.ico",
            ]
        );
    }

    #[test]
    fn a_page_without_links_leaves_the_usual_place() {
        assert_eq!(
            icon_links("example.com", "<html><link rel=icon"),
            ["https://example.com/favicon.ico"]
        );
        assert_eq!(icon_links("example.com", "").len(), 1);
    }

    #[test]
    fn known_services_get_their_kind() {
        assert_eq!(
            icon_for_url("https://github.com/login"),
            Some(icon::TERMINAL)
        );
        assert_eq!(
            icon_for_url("https://www.Sberbank.ru:443/x?y#z"),
            Some(icon::BANK)
        );
        assert_eq!(icon_for_url("accounts.paypal.com"), Some(icon::MONEY));
        assert_eq!(
            icon_for_url("https://user:pw@mail.google.com/"),
            Some(icon::MAIL)
        );
        assert_eq!(icon_for_url("https://t.me/someone"), Some(icon::CHAT));
        assert_eq!(
            icon_for_url("https://www.gosuslugi.ru"),
            Some(icon::CERTIFICATE)
        );
    }

    #[test]
    fn the_more_specific_address_wins() {
        assert_eq!(
            icon_for_url("https://aws.amazon.com/console"),
            Some(icon::SERVER)
        );
        assert_eq!(
            icon_for_url("https://www.amazon.com/orders"),
            Some(icon::SHOPPING)
        );
        assert_eq!(
            icon_for_url("https://market.yandex.ru"),
            Some(icon::SHOPPING)
        );
        assert_eq!(icon_for_url("https://music.yandex.ru"), Some(icon::MEDIA));
        assert_eq!(
            icon_for_url("https://drive.google.com"),
            Some(icon::ARCHIVE)
        );
    }

    #[test]
    fn a_domain_is_matched_whole_not_by_its_ending() {
        assert_eq!(
            icon_for_url("https://notgithub.com"),
            Some(icon::TERMINAL),
            "the word git"
        );
        assert_eq!(
            icon_for_url("https://fakex.com"),
            Some(icon::WEB),
            "not x.com"
        );
        assert_eq!(
            icon_for_url("https://myvk.com.example.org"),
            Some(icon::WEB)
        );
    }

    #[test]
    fn words_in_the_host_help_with_unknown_sites() {
        assert_eq!(
            icon_for_url("https://online.examplebank.com"),
            Some(icon::BANK)
        );
        assert_eq!(
            icon_for_url("https://mail.example.org/login"),
            Some(icon::MAIL)
        );
        assert_eq!(
            icon_for_url("https://shop.example.com"),
            Some(icon::SHOPPING)
        );
        assert_eq!(
            icon_for_url("https://example.pay"),
            Some(icon::WEB),
            "a top-level domain is not a word"
        );
        assert_eq!(
            icon_for_url("https://data.example.gov"),
            Some(icon::CERTIFICATE)
        );
        assert_eq!(icon_for_url("https://lib.example.edu"), Some(icon::BOOK));
    }

    #[test]
    fn other_addresses_get_the_globe_and_non_addresses_nothing() {
        assert_eq!(icon_for_url("https://example.org"), Some(icon::WEB));
        assert_eq!(icon_for_url("https://пример.испытание"), Some(icon::WEB));
        assert_eq!(icon_for_url("192.168.1.1"), Some(icon::WEB));
        assert_eq!(
            icon_for_url("androidapp://com.example.app"),
            Some(icon::PHONE)
        );
        assert_eq!(
            icon_for_url("ssh://root@box.example.net"),
            Some(icon::SERVER)
        );
        assert_eq!(icon_for_url(""), None);
        assert_eq!(icon_for_url("   "), None);
        assert_eq!(icon_for_url("just some words"), None);
        assert_eq!(icon_for_url("localhost"), None);
    }
}
