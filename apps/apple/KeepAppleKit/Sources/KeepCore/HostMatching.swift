import KeepCoreBindings

/// How a requesting host relates to the host saved in an entry (core `origin` module).
public enum HostMatch: Equatable, Sendable {
    case exact
    case subdomain
    case none

    /// Hosts must be IDNA-encoded (punycode); comparison is exact after ASCII lowercasing.
    public init(entryHost: String, requestHost: String) {
        switch matchHost(entryHost: entryHost, requestHost: requestHost) {
        case .exact: self = .exact
        case .subdomain: self = .subdomain
        case .none: self = .none
        }
    }
}
