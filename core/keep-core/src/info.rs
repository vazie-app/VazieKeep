#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CoreInfo {
    pub name: &'static str,
    pub version: &'static str,
}

pub fn core_info() -> CoreInfo {
    CoreInfo {
        name: env!("CARGO_PKG_NAME"),
        version: env!("CARGO_PKG_VERSION"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reports_crate_version() {
        let info = core_info();
        assert_eq!(info.name, "keep-core");
        assert!(!info.version.is_empty());
    }
}
