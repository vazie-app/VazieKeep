import Foundation
import KeepCore
import Observation
import Security

/// Everything the app keeps about a Vazie Account on this device.
public struct StoredAccount: Codable, Equatable, Sendable, CustomStringConvertible {
    public var accountID: String
    public var email: String?
    public var sessionToken: String
    public var leaseToken: String?
    /// The latest device time seen, so setting the clock back cannot extend a lease.
    public var lastObservedDeviceTime: Int64
    /// Wall-clock seconds of the last successful refresh, for the 24-hour cadence.
    public var lastRefreshAt: Int64

    public init(accountID: String, email: String?, sessionToken: String, leaseToken: String?, lastObservedDeviceTime: Int64, lastRefreshAt: Int64) {
        self.accountID = accountID
        self.email = email
        self.sessionToken = sessionToken
        self.leaseToken = leaseToken
        self.lastObservedDeviceTime = lastObservedDeviceTime
        self.lastRefreshAt = lastRefreshAt
    }

    public var description: String { "StoredAccount(accountID: \(accountID))" }
}

public protocol AccountStore: Sendable {
    func load() -> StoredAccount?
    func save(_ account: StoredAccount)
    func clear()
}

/// The account lives in the Keychain, readable after the first unlock and never synced or backed up.
/// The item carries the installation id as its generic attribute: Keychain items can outlive an
/// uninstall, and an item from another installation is deleted rather than trusted.
public struct KeychainAccountStore: AccountStore {
    private let service: String
    private let installation: @Sendable () -> Data

    public init(service: String = "app.vazie.keep.account", installation: @escaping @Sendable () -> Data = KeychainAccountStore.installationID) {
        self.service = service
        self.installation = installation
    }

    private func base() -> [CFString: Any] {
        [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: "account"]
    }

    public func load() -> StoredAccount? {
        var query = base()
        query[kSecReturnData] = true
        query[kSecReturnAttributes] = true
        query[kSecMatchLimit] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let item = result as? [CFString: Any], let data = item[kSecValueData] as? Data else { return nil }
        guard let owner = item[kSecAttrGeneric] as? Data, owner == installation() else {
            SecItemDelete(base() as CFDictionary)
            return nil
        }
        return try? JSONDecoder().decode(StoredAccount.self, from: data)
    }

    public func save(_ account: StoredAccount) {
        guard let data = try? JSONEncoder().encode(account) else { return }
        SecItemDelete(base() as CFDictionary)
        var item = base()
        item[kSecValueData] = data
        item[kSecAttrGeneric] = installation()
        item[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        item[kSecAttrSynchronizable] = false
        SecItemAdd(item as CFDictionary, nil)
    }

    public func clear() {
        SecItemDelete(base() as CFDictionary)
    }

    /// Random per installation; it lives in the sandbox, so a reinstall gets a new one.
    public static func installationID() -> Data {
        let url = URL.applicationSupportDirectory.appending(path: "account-installation")
        if let existing = try? Data(contentsOf: url), existing.count == 16 { return existing }
        var generator = SystemRandomNumberGenerator()
        let created = Data((0..<16).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? created.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        return created
    }
}

public struct ApiSession: Sendable {
    public let accountID: String
    public let email: String?
    public let token: String

    public init(accountID: String, email: String?, token: String) {
        self.accountID = accountID
        self.email = email
        self.token = token
    }
}

/// Why a call to the Vazie backend did not work. None of these ever concerns a vault.
public enum AccountApiError: Error, Equatable, Sendable {
    case unreachable, sessionRejected, invalidCode, invalidEmail, rateLimited, notEnabled
}

/// The two things Keep asks of the Vazie backend: sign in, and fetch a signed lease. No vault data,
/// no device identifier and no vault count is ever sent.
public protocol VazieAccountApi: Sendable {
    func startEmailSignIn(email: String) async throws(AccountApiError)
    func verifyEmailCode(email: String, code: String) async throws(AccountApiError) -> ApiSession
    /// Only a development backend answers this; the production one has it switched off.
    func devSignIn() async throws(AccountApiError) -> ApiSession
    func fetchLease(token: String) async throws(AccountApiError) -> String
    func signOut(token: String) async throws(AccountApiError)
    /// Deletes the account for good: its address, sessions and Keep+. Every charge for it ends with it.
    func deleteAccount(token: String) async throws(AccountApiError)
}

public struct HTTPVazieAccountApi: VazieAccountApi {
    private let accountOrigin: URL
    private let keepOrigin: URL
    private let session: URLSession

    public init(accountOrigin: URL, keepOrigin: URL, session: URLSession = HTTPVazieAccountApi.makeSession()) {
        self.accountOrigin = accountOrigin
        self.keepOrigin = keepOrigin
        self.session = session
    }

    /// Ephemeral: no cache, cookies or credential storage for account traffic.
    public static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }

    public func startEmailSignIn(email: String) async throws(AccountApiError) {
        _ = try await send("POST", accountOrigin, "/v1/auth/email/start", body: ["email": email])
    }

    public func verifyEmailCode(email: String, code: String) async throws(AccountApiError) -> ApiSession {
        try Self.session(from: await send("POST", accountOrigin, "/v1/auth/email/verify", body: ["email": email, "code": code, "platform": "IOS"]))
    }

    public func devSignIn() async throws(AccountApiError) -> ApiSession {
        try Self.session(from: await send("POST", accountOrigin, "/api/v1/auth/dev", body: ["platform": "IOS"]))
    }

    public func fetchLease(token: String) async throws(AccountApiError) -> String {
        guard let lease = try await send("GET", keepOrigin, "/v1/entitlement", bearer: token)["lease"] as? String, !lease.isEmpty else {
            throw .unreachable
        }
        return lease
    }

    public func signOut(token: String) async throws(AccountApiError) {
        _ = try await send("DELETE", accountOrigin, "/v1/session", bearer: token)
    }

    public func deleteAccount(token: String) async throws(AccountApiError) {
        _ = try await send("DELETE", accountOrigin, "/v1/account", body: ["confirm": "DELETE"], bearer: token)
    }

    private static func session(from json: [String: Any]) throws(AccountApiError) -> ApiSession {
        guard let id = json["accountId"] as? String, !id.isEmpty, let token = json["token"] as? String, !token.isEmpty else { throw .unreachable }
        return ApiSession(accountID: id, email: (json["email"] as? String).flatMap { $0.isEmpty ? nil : $0 }, token: token)
    }

    private func send(_ method: String, _ origin: URL, _ path: String, body: [String: String]? = nil, bearer: String? = nil) async throws(AccountApiError) -> [String: Any] {
        guard let url = URL(string: path, relativeTo: origin) else { throw .unreachable }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let bearer { request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw .unreachable
        }
        guard let http = response as? HTTPURLResponse else { throw .unreachable }
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard (200..<300).contains(http.statusCode) else { throw Self.failure(status: http.statusCode, json: json) }
        return json
    }

    private static func failure(status: Int, json: [String: Any]) -> AccountApiError {
        let code = (json["error"] as? [String: Any])?["code"] as? String
        switch (code, status) {
        case ("EMAIL_CODE_INVALID", _): return .invalidCode
        case ("AUTH_INVALID", _), ("AUTH_REQUIRED", _), (_, 401): return .sessionRejected
        case ("NOT_ENABLED", _): return .notEnabled
        case ("RATE_LIMITED", _), (_, 429): return .rateLimited
        case ("VALIDATION_FAILED", _): return .invalidEmail
        default: return .unreachable
        }
    }
}
