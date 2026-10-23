import Foundation
import LocalAuthentication
import Security

/// Face ID (or Touch ID) only. There is no passcode fallback: the fallback button hands control
/// back to the master password field.
public struct LocalBiometricAuthenticator: BiometricAuthenticator {
    private let fallbackTitle: String

    public init(fallbackTitle: String) {
        self.fallbackTitle = fallbackTitle
    }

    public func availability() -> BiometricAvailability {
        let context = LAContext()
        var error: NSError?
        if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) { return .available }
        return Self.availability(for: error.map { LAError.Code(rawValue: $0.code) } ?? nil, biometryType: context.biometryType)
    }

    public func enrollmentState() -> Data? {
        let context = LAContext()
        var error: NSError?
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
        return context.domainState.biometry.stateHash
    }

    public func authenticate(reason: String) async -> BiometricAuthResult {
        let context = LAContext()
        context.localizedFallbackTitle = fallbackTitle
        do {
            try await context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason)
            return .success(BiometricAuthorization(context: context))
        } catch let error as LAError {
            switch error.code {
            case .userCancel, .systemCancel, .appCancel: return .cancelled
            case .userFallback: return .fallback
            case .biometryLockout: return .lockedOut
            case .biometryNotEnrolled, .biometryNotAvailable, .passcodeNotSet:
                return .unavailable(Self.availability(for: error.code, biometryType: context.biometryType))
            default: return .failed
            }
        } catch {
            return .failed
        }
    }

    static func availability(for code: LAError.Code?, biometryType: LABiometryType) -> BiometricAvailability {
        switch code {
        case .biometryNotEnrolled: .notEnrolled
        case .passcodeNotSet: .passcodeNotSet
        case .biometryLockout: .lockedOut
        case .biometryNotAvailable: biometryType == .none ? .noHardware : .denied
        default: .noHardware
        }
    }
}

/// One generic-password item per setup. The item needs the current Face ID enrollment
/// (`biometryCurrentSet`), exists only while a passcode is set, never leaves this device and is
/// never synchronized. Its data is the only secret; the account and generic attributes are a
/// random setup id and the installation id. It lives in the access group the app and the
/// AutoFill extension share (`<team>.app.vazie.keep.shared`), and nowhere broader.
public struct KeychainCredentialStore: BiometricCredentialStore {
    public static let defaultService = "app.vazie.keep.face-id-unlock"
    /// Set at build time from `$(AppIdentifierPrefix)` in each target's Info.plist.
    public static var sharedAccessGroup: String? {
        Bundle.main.object(forInfoDictionaryKey: "VazieKeychainGroup") as? String
    }

    private let service: String
    private let accessGroup: String?

    public init(service: String = KeychainCredentialStore.defaultService, accessGroup: String? = KeychainCredentialStore.sharedAccessGroup) {
        self.service = service
        self.accessGroup = accessGroup
    }

    public func create(id: String, installation: Data, material: Data, authorization: BiometricAuthorization) throws(BiometricStoreError) {
        SecItemDelete(base(id) as CFDictionary)
        try add(id: id, installation: installation, material: material, authorization: authorization)
    }

    /// Searches every group this process may read. An item still in the app's own group (made
    /// before the AutoFill extension existed) is moved into the shared group with the same access
    /// control, using the context that was just authenticated.
    public func read(id: String, installation: Data, authorization: BiometricAuthorization) throws(BiometricStoreError) -> Data {
        var query = base(id)
        query[kSecAttrGeneric] = installation
        query[kSecReturnData] = true
        query[kSecReturnAttributes] = true
        query[kSecMatchLimit] = kSecMatchLimitOne
        query[kSecUseAuthenticationContext] = authorization.context ?? Self.silentContext()
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { throw Self.error(status) }
        guard let item = result as? [CFString: Any], let data = item[kSecValueData] as? Data else { throw .notFound }
        if let accessGroup, let group = item[kSecAttrAccessGroup] as? String, group != accessGroup {
            var legacy = base(id)
            legacy[kSecAttrAccessGroup] = group
            if (try? add(id: id, installation: installation, material: data, authorization: authorization)) != nil {
                SecItemDelete(legacy as CFDictionary)
            }
        }
        return data
    }

    public func contains(id: String, installation: Data) -> Bool {
        var query = base(id)
        query[kSecAttrGeneric] = installation
        query[kSecMatchLimit] = kSecMatchLimitOne
        query[kSecUseAuthenticationContext] = Self.silentContext()
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    public func remove(id: String) {
        SecItemDelete(base(id) as CFDictionary)
    }

    @discardableResult
    public func removeAll() -> Bool {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrSynchronizable: kSecAttrSynchronizableAny,
            kSecUseDataProtectionKeychain: true,
        ]
        return SecItemDelete(query as CFDictionary) == errSecSuccess
    }

    private func add(id: String, installation: Data, material: Data, authorization: BiometricAuthorization) throws(BiometricStoreError) {
        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .biometryCurrentSet, &error) else {
            throw .failed(errSecParam)
        }
        var attributes = base(id)
        attributes[kSecAttrGeneric] = installation
        attributes[kSecAttrAccessControl] = access
        attributes[kSecValueData] = material
        if let accessGroup { attributes[kSecAttrAccessGroup] = accessGroup }
        if let context = authorization.context { attributes[kSecUseAuthenticationContext] = context }
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw Self.error(status) }
    }

    private func base(_ id: String) -> [CFString: Any] {
        [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: id,
            kSecAttrSynchronizable: false,
            kSecUseDataProtectionKeychain: true,
        ]
    }

    private static func silentContext() -> LAContext {
        let context = LAContext()
        context.interactionNotAllowed = true
        return context
    }

    static func error(_ status: OSStatus) -> BiometricStoreError {
        switch status {
        case errSecItemNotFound: .notFound
        case errSecAuthFailed: .invalidated
        case errSecUserCanceled: .cancelled
        case errSecInteractionNotAllowed: .interactionNotAllowed
        default: .failed(status)
        }
    }
}

/// The non-secret setup record and the installation id, in the shared container. Both disappear
/// when the app (with its extension) is deleted and are kept out of backups, which is what ties a
/// Keychain item to one installation on one device; the app and its extension share both.
public struct FileBiometricSetupStore: BiometricSetupStore {
    private let record: URL
    private let others: URL
    private let installation: URL

    public init(directory: URL) {
        record = directory.appending(path: "face-id-unlock.json")
        others = directory.appending(path: "face-id-unlock-vaults.json")
        installation = directory.appending(path: "installation-id")
    }

    public static func shared() -> FileBiometricSetupStore {
        FileBiometricSetupStore(directory: SharedContainer.directory())
    }

    public func installationID() -> Data {
        if let existing = try? Data(contentsOf: installation), existing.count == 16 { return existing }
        var generator = SystemRandomNumberGenerator()
        let created = Data((0..<16).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
        try? write(created, to: installation)
        return created
    }

    public func load() -> BiometricSetup? {
        guard let data = try? Data(contentsOf: record) else { return nil }
        return try? JSONDecoder().decode(BiometricSetup.self, from: data)
    }

    public func save(_ setup: BiometricSetup) throws {
        try write(try JSONEncoder().encode(setup), to: record)
    }

    public func clear() {
        try? FileManager.default.removeItem(at: record)
    }

    public func loadOthers() -> [String: BiometricSetup] {
        guard let data = try? Data(contentsOf: others) else { return [:] }
        return (try? JSONDecoder().decode([String: BiometricSetup].self, from: data)) ?? [:]
    }

    public func saveOthers(_ setups: [String: BiometricSetup]) {
        guard !setups.isEmpty else {
            try? FileManager.default.removeItem(at: others)
            return
        }
        guard let data = try? JSONEncoder().encode(setups) else { return }
        try? write(data, to: others)
    }

    private func write(_ data: Data, to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: url, options: [.atomic])
        #endif
        var excluded = URLResourceValues()
        excluded.isExcludedFromBackup = true
        var target = url
        try target.setResourceValues(excluded)
    }
}
