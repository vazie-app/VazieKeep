import AuthenticationServices
import Foundation
import KeepCore

/// A web origin: scheme, IDNA-encoded host and an explicit port, if any.
public struct ServiceOrigin: Equatable, Hashable, Sendable {
    public var scheme: String
    public var host: String
    public var port: Int?

    public init(scheme: String = "https", host: String, port: Int? = nil) {
        self.scheme = scheme
        self.host = host
        self.port = port
    }

    /// An entry's URL field. A bare host (`example.com/login`) is read as https. Only http and
    /// https entries can be filled; anything else (`androidapp://`, `ssh://`) has no origin.
    public init?(entryURL: String) {
        let trimmed = entryURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let text = trimmed.contains("://") ? trimmed : "https://" + trimmed
        self.init(url: text)
    }

    /// What iOS reports for the page or app asking for a credential.
    public init?(serviceIdentifier: String, isDomain: Bool) {
        if isDomain {
            self.init(url: "https://" + serviceIdentifier)
        } else {
            self.init(url: serviceIdentifier)
        }
    }

    private init?(url text: String) {
        // `encodedHost` is IDNA (punycode), so a Unicode host can't pose as an ASCII one.
        guard let url = URLComponents(string: text), let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http",
              var host = url.encodedHost?.lowercased(), !host.isEmpty else { return nil }
        if host.hasSuffix(".") { host.removeLast() }
        if host.hasPrefix("[") && host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
        self.scheme = scheme
        self.host = host
        let defaultPort = scheme == "https" ? 443 : 80
        port = url.port.flatMap { $0 == defaultPort ? nil : $0 }
    }
}

public enum OriginMatch: Int, Comparable, Sendable {
    case subdomain
    case exact

    public static func < (lhs: OriginMatch, rhs: OriginMatch) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// Which entries may be suggested for a request. Hosts follow the core policy (exact host or a
/// subdomain of a registrable saved host, never a look-alike or a public suffix). On top of that
/// the port must be the same, and an https entry is never offered to an http page.
public enum AutoFillMatcher {
    public static func match(entry: ServiceOrigin, request: ServiceOrigin) -> OriginMatch? {
        guard entry.port == request.port else { return nil }
        if entry.scheme == "https" && request.scheme != "https" { return nil }
        switch HostMatch(entryHost: entry.host, requestHost: request.host) {
        case .exact: return .exact
        case .subdomain: return .subdomain
        case .none: return nil
        }
    }

    /// The best match of an entry against any of the requested services.
    public static func match(_ entry: EntrySummary, requests: [ServiceOrigin]) -> OriginMatch? {
        guard case let .plain(url) = entry.url, let origin = ServiceOrigin(entryURL: url) else { return nil }
        return requests.compactMap { match(entry: origin, request: $0) }.max()
    }
}

/// What iOS may show as a QuickType suggestion before the vault is unlocked. No secrets: the
/// saved host, the username and an opaque record id.
public struct AutoFillIdentity: Equatable, Hashable, Sendable {
    public var host: String
    public var user: String
    public var recordIdentifier: String

    public init(host: String, user: String, recordIdentifier: String) {
        self.host = host
        self.user = user
        self.recordIdentifier = recordIdentifier
    }
}

/// A passkey iOS may offer before the vault is unlocked: the site, the account and the public identifiers.
public struct PasskeyIdentity: Equatable, Hashable, Sendable {
    public var rpID: String
    public var userName: String
    public var credentialID: Data
    public var userHandle: Data
    public var recordIdentifier: String

    public init(rpID: String, userName: String, credentialID: Data, userHandle: Data, recordIdentifier: String) {
        self.rpID = rpID
        self.userName = userName
        self.credentialID = credentialID
        self.userHandle = userHandle
        self.recordIdentifier = recordIdentifier
    }
}

public enum AutoFillIndex {
    /// Ties a record to one database: `<first 16 hex of the vault fingerprint>/<entry id>`, so a
    /// suggestion left from another vault never resolves to an entry in this one.
    public static func recordIdentifier(fingerprint: String, entry: EntryID) -> String {
        "\(fingerprint.prefix(16))/\(entry.rawValue)"
    }

    public static func parse(recordIdentifier: String, fingerprint: String) -> EntryID? {
        let parts = recordIdentifier.split(separator: "/", maxSplits: 1)
        guard parts.count == 2, parts[0] == fingerprint.prefix(16), !parts[1].isEmpty else { return nil }
        return EntryID(String(parts[1]))
    }

    public static func passkeyIdentities(for entries: [EntrySummary], fingerprint: String) -> [PasskeyIdentity] {
        entries.compactMap { entry in
            guard let passkey = entry.passkey, let credential = passkey.credentialIDData, !credential.isEmpty else { return nil }
            return PasskeyIdentity(
                rpID: passkey.rpID,
                userName: passkey.userName.isEmpty ? entry.displayTitle : passkey.userName,
                credentialID: credential,
                userHandle: passkey.userHandleData ?? Data(),
                recordIdentifier: recordIdentifier(fingerprint: fingerprint, entry: entry.id)
            )
        }
    }

    /// Entries with a password, a username and an http(s) URL. Built from summaries only: no
    /// password is revealed to build the index.
    public static func identities(for entries: [EntrySummary], fingerprint: String) -> [AutoFillIdentity] {
        entries.compactMap { entry in
            guard entry.hasPassword, case let .plain(user) = entry.username, !user.isEmpty,
                  case let .plain(url) = entry.url, let origin = ServiceOrigin(entryURL: url), origin.port == nil else { return nil }
            return AutoFillIdentity(host: origin.host, user: user, recordIdentifier: recordIdentifier(fingerprint: fingerprint, entry: entry.id))
        }
    }
}

public protocol CredentialIdentityRegistry: Sendable {
    func replace(with identities: [AutoFillIdentity], passkeys: [PasskeyIdentity]) async -> Bool
    func removeAll() async
    /// Whether the user turned Vazie Keep on in the AutoFill settings.
    func isEnabled() async -> Bool
    func count() async -> Int
}

/// The system `ASCredentialIdentityStore`. Identities hold the host and the username, which iOS
/// keeps to offer suggestions; never a password.
public struct SystemCredentialIdentityRegistry: CredentialIdentityRegistry {
    public init() {}

    public func replace(with identities: [AutoFillIdentity], passkeys: [PasskeyIdentity]) async -> Bool {
        let store = ASCredentialIdentityStore.shared
        guard await store.state().isEnabled else { return false }
        var entries: [any ASCredentialIdentity] = identities.map {
            ASPasswordCredentialIdentity(
                serviceIdentifier: ASCredentialServiceIdentifier(identifier: $0.host, type: .domain),
                user: $0.user,
                recordIdentifier: $0.recordIdentifier
            )
        }
        entries += passkeys.map {
            ASPasskeyCredentialIdentity(relyingPartyIdentifier: $0.rpID, userName: $0.userName, credentialID: $0.credentialID, userHandle: $0.userHandle, recordIdentifier: $0.recordIdentifier)
        }
        do {
            try await store.replaceCredentialIdentities(entries)
            return true
        } catch {
            return false
        }
    }

    public func removeAll() async {
        try? await ASCredentialIdentityStore.shared.removeAllCredentialIdentities()
    }

    public func isEnabled() async -> Bool {
        await ASCredentialIdentityStore.shared.state().isEnabled
    }

    public func count() async -> Int {
        await ASCredentialIdentityStore.shared.credentialIdentities(forService: nil, credentialIdentityTypes: .password).count
    }
}

/// Keeps the system's suggestions in step with the vault: rebuilt after each unlock in the app,
/// cleared when the vault is switched or forgotten.
@MainActor
public final class AutoFillIndexer {
    private let controller: VaultSessionController
    private let registry: any CredentialIdentityRegistry
    private let log: (String) -> Void
    private var work: Task<Void, Never>?

    public init(controller: VaultSessionController, registry: any CredentialIdentityRegistry, log: @escaping (String) -> Void = { _ in }) {
        self.controller = controller
        self.registry = registry
        self.log = log
    }

    public func vaultUnlocked() {
        guard case let .unlocked(info) = controller.state, let entries = try? controller.entries() else { return }
        let identities = AutoFillIndex.identities(for: entries, fingerprint: info.fingerprint)
        let passkeys = AutoFillIndex.passkeyIdentities(for: controller.passkeys(rpID: nil), fingerprint: info.fingerprint)
        let registry = registry
        let log = log
        work?.cancel()
        work = Task {
            let saved = await registry.replace(with: identities, passkeys: passkeys)
            log(saved ? "autofill: \(identities.count) identities, \(passkeys.count) passkeys" : "autofill: provider off, index not saved")
        }
    }

    public func clear() {
        let registry = registry
        let log = log
        work?.cancel()
        work = Task {
            await registry.removeAll()
            log("autofill: index cleared")
        }
    }

    /// For tests: waits for the last update.
    public func settle() async {
        await work?.value
    }
}
