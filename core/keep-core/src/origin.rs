//! Which saved entry may be offered on which site.
//!
//! An entry saved for `example.com` is offered on `example.com` and on its subdomains such as
//! `accounts.example.com`. Nothing else matches: not a parent (`example.com` for an entry saved
//! on `accounts.example.com`), not a sibling, not a look-alike (`paypaI.com`), and never a whole
//! public suffix (`co.uk`, `github.io`), which would cover sites run by other people. Hosts are
//! compared exactly, after ASCII lowercasing; callers pass IDNA-encoded (punycode) hosts.

/// How a requesting host relates to the host saved in an entry.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HostMatch {
    Exact,
    Subdomain,
    None,
}

#[must_use]
pub fn match_host(entry_host: &str, request_host: &str) -> HostMatch {
    let (Some(entry), Some(request)) = (normalize(entry_host), normalize(request_host)) else {
        return HostMatch::None;
    };
    if entry == request {
        return HostMatch::Exact;
    }
    if is_ip(&entry) || is_ip(&request) || !owns_subdomains(&entry) {
        return HostMatch::None;
    }
    match request.strip_suffix(entry.as_str()) {
        Some(prefix) if prefix.ends_with('.') && prefix.len() > 1 => HostMatch::Subdomain,
        _ => HostMatch::None,
    }
}

/// True when the host is a registrable domain or below one, so its subdomains belong to the same
/// owner. A public suffix (`com`, `co.uk`, `github.io`) or a single label owns nothing.
fn owns_subdomains(host: &str) -> bool {
    psl::domain(host.as_bytes()).is_some_and(|domain| domain.suffix().is_known())
}

fn normalize(host: &str) -> Option<String> {
    let host = host.trim();
    let host = host.strip_suffix('.').unwrap_or(host);
    let valid = !host.is_empty()
        && host.len() <= 253
        && host
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b':'))
        && !host.starts_with('.')
        && !host.contains("..");
    valid.then(|| host.to_ascii_lowercase())
}

fn is_ip(host: &str) -> bool {
    host.contains(':') || host.parse::<std::net::Ipv4Addr>().is_ok()
}

#[cfg(test)]
mod tests {
    use super::{HostMatch, match_host};

    #[test]
    fn exact_hosts_match_regardless_of_case_and_a_trailing_dot() {
        assert_eq!(match_host("example.com", "example.com"), HostMatch::Exact);
        assert_eq!(match_host("Example.COM", "example.com."), HostMatch::Exact);
        assert_eq!(
            match_host("xn--bcher-kva.de", "xn--bcher-kva.de"),
            HostMatch::Exact
        );
    }

    #[test]
    fn subdomains_of_the_saved_site_match() {
        assert_eq!(
            match_host("example.com", "accounts.example.com"),
            HostMatch::Subdomain
        );
        assert_eq!(
            match_host("example.com", "a.b.example.com"),
            HostMatch::Subdomain
        );
        assert_eq!(
            match_host("example.co.uk", "login.example.co.uk"),
            HostMatch::Subdomain
        );
        assert_eq!(
            match_host("accounts.example.com", "eu.accounts.example.com"),
            HostMatch::Subdomain
        );
    }

    #[test]
    fn parents_siblings_and_other_domains_never_match() {
        assert_eq!(
            match_host("accounts.example.com", "example.com"),
            HostMatch::None
        );
        assert_eq!(
            match_host("accounts.example.com", "mail.example.com"),
            HostMatch::None
        );
        assert_eq!(match_host("example.com", "example.net"), HostMatch::None);
        assert_eq!(match_host("example.com", "notexample.com"), HostMatch::None);
        assert_eq!(
            match_host("example.com", "example.com.evil.net"),
            HostMatch::None
        );
        assert_eq!(match_host("example.com", ".example.com"), HostMatch::None);
    }

    #[test]
    fn look_alikes_are_different_hosts() {
        assert_eq!(match_host("paypal.com", "paypai.com"), HostMatch::None);
        assert_eq!(match_host("paypal.com", "paypaI.com"), HostMatch::None);
        assert_eq!(
            match_host("paypal.com", "xn--paypa-7ve.com"),
            HostMatch::None
        );
        assert_eq!(match_host("paypal.com", "paypal.com.co"), HostMatch::None);
    }

    #[test]
    fn a_public_suffix_never_covers_other_peoples_sites() {
        assert_eq!(match_host("co.uk", "bank.co.uk"), HostMatch::None);
        assert_eq!(match_host("com", "example.com"), HostMatch::None);
        assert_eq!(
            match_host("github.io", "attacker.github.io"),
            HostMatch::None
        );
        assert_eq!(
            match_host("alice.github.io", "bob.github.io"),
            HostMatch::None
        );
        assert_eq!(
            match_host("alice.github.io", "alice.github.io"),
            HostMatch::Exact
        );
        assert_eq!(match_host("localhost", "app.localhost"), HostMatch::None);
        assert_eq!(match_host("intranet", "wiki.intranet"), HostMatch::None);
    }

    #[test]
    fn ip_addresses_match_only_exactly() {
        assert_eq!(match_host("192.168.1.1", "192.168.1.1"), HostMatch::Exact);
        assert_eq!(match_host("168.1.1", "192.168.1.1"), HostMatch::None);
        assert_eq!(match_host("::1", "::1"), HostMatch::Exact);
    }

    #[test]
    fn malformed_hosts_never_match() {
        assert_eq!(match_host("", ""), HostMatch::None);
        assert_eq!(match_host("example..com", "example..com"), HostMatch::None);
        assert_eq!(match_host("exa mple.com", "exa mple.com"), HostMatch::None);
        assert_eq!(match_host("bücher.de", "bücher.de"), HostMatch::None);
        assert_eq!(
            match_host("example.com/path", "example.com/path"),
            HostMatch::None
        );
    }
}
