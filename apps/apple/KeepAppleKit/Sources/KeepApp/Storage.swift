import Foundation
import Observation

public struct VaultReference: Codable, Equatable, Sendable {
    public var vault: DocumentReference
    public var keyFile: DocumentReference?

    public init(vault: DocumentReference, keyFile: DocumentReference? = nil) {
        self.vault = vault
        self.keyFile = keyFile
    }
}

public protocol VaultReferenceStore: Sendable {
    func load() -> VaultReference?
    func save(_ reference: VaultReference)
    func clear()
}

/// Bookmarks only. Kept out of backups: a bookmark restored on another device can't resolve.
public struct FileVaultReferenceStore: VaultReferenceStore {
    private let url: URL

    public init(directory: URL) {
        url = directory.appending(path: "vault-reference.json")
    }

    public static func shared() -> FileVaultReferenceStore {
        FileVaultReferenceStore(directory: SharedContainer.directory())
    }

    public func load() -> VaultReference? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(VaultReference.self, from: data)
    }

    public func save(_ reference: VaultReference) {
        guard let data = try? JSONEncoder().encode(reference) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS)
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try? data.write(to: url, options: [.atomic])
        #endif
        var excluded = URLResourceValues()
        excluded.isExcludedFromBackup = true
        var target = url
        try? target.setResourceValues(excluded)
    }

    public func clear() {
        try? FileManager.default.removeItem(at: url)
    }
}

public enum AutoLockTimeout: String, CaseIterable, Sendable {
    case immediately, seconds15, seconds30, minute1, minutes5, minutes15

    public var duration: Duration {
        switch self {
        case .immediately: .zero
        case .seconds15: .seconds(15)
        case .seconds30: .seconds(30)
        case .minute1: .seconds(60)
        case .minutes5: .seconds(300)
        case .minutes15: .seconds(900)
        }
    }
}

public enum ClipboardTimeout: String, CaseIterable, Sendable {
    case never, seconds15, seconds30, seconds60

    public var duration: Duration? {
        switch self {
        case .never: nil
        case .seconds15: .seconds(15)
        case .seconds30: .seconds(30)
        case .seconds60: .seconds(60)
        }
    }
}

public enum ThemeMode: String, CaseIterable, Sendable {
    case system, light, dark
}

public struct SecuritySettings: Equatable, Sendable {
    public var autoLock: AutoLockTimeout = .seconds30
    public var clipboard: ClipboardTimeout = .seconds30
    public var lockWhenProtectedDataUnavailable = true
    public var theme: ThemeMode = .system
    /// Off unless the person turns it on: saving an entry then asks its website for an icon.
    public var siteIconsAutomatic = false

    public init() {}
}

/// Non-secret preferences in `UserDefaults`. Unknown or missing values fall back to the defaults.
@MainActor
@Observable
public final class SettingsStore {
    public private(set) var settings: SecuritySettings
    @ObservationIgnored private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        var settings = SecuritySettings()
        if let value = defaults.string(forKey: Keys.autoLock).flatMap(AutoLockTimeout.init(rawValue:)) { settings.autoLock = value }
        if let value = defaults.string(forKey: Keys.clipboard).flatMap(ClipboardTimeout.init(rawValue:)) { settings.clipboard = value }
        if defaults.object(forKey: Keys.protectedData) != nil { settings.lockWhenProtectedDataUnavailable = defaults.bool(forKey: Keys.protectedData) }
        if let value = defaults.string(forKey: Keys.theme).flatMap(ThemeMode.init(rawValue:)) { settings.theme = value }
        settings.siteIconsAutomatic = defaults.bool(forKey: Keys.siteIcons)
        self.settings = settings
    }

    public func setSiteIconsAutomatic(_ value: Bool) {
        settings.siteIconsAutomatic = value
        defaults.set(value, forKey: Keys.siteIcons)
    }

    public func setAutoLock(_ value: AutoLockTimeout) {
        settings.autoLock = value
        defaults.set(value.rawValue, forKey: Keys.autoLock)
    }

    public func setClipboard(_ value: ClipboardTimeout) {
        settings.clipboard = value
        defaults.set(value.rawValue, forKey: Keys.clipboard)
    }

    public func setLockWhenProtectedDataUnavailable(_ value: Bool) {
        settings.lockWhenProtectedDataUnavailable = value
        defaults.set(value, forKey: Keys.protectedData)
    }

    public func setTheme(_ value: ThemeMode) {
        settings.theme = value
        defaults.set(value.rawValue, forKey: Keys.theme)
    }

    private enum Keys {
        static let autoLock = "security.autoLock"
        static let clipboard = "security.clipboard"
        static let protectedData = "security.lockWhenProtectedDataUnavailable"
        static let theme = "appearance.theme"
        static let siteIcons = "appearance.siteIconsAutomatic"
    }
}
