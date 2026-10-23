import Foundation

/// Encrypted copies of a vault kept in the app's own container, never in the user's folder:
/// the version that the last save replaced, and changes that couldn't be saved before a lock.
/// Both are complete KDBX files, as encrypted as the vault itself.
public protocol VaultCopyStore: Sendable {
    func keepBackup(_ data: Data, fingerprint: String) -> Bool
    func backup(fingerprint: String) -> Data?
    func keepUnsaved(_ data: Data, fingerprint: String) -> Bool
    func unsaved(fingerprint: String) -> Data?
    func removeUnsaved(fingerprint: String)
}

public struct FileVaultCopyStore: VaultCopyStore {
    private let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    /// App-private, not the App Group: the AutoFill extension never writes.
    public static func applicationSupport() -> FileVaultCopyStore {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        return FileVaultCopyStore(directory: base.appending(path: "Vault Copies", directoryHint: .isDirectory))
    }

    public func keepBackup(_ data: Data, fingerprint: String) -> Bool {
        write(data, to: url(fingerprint, "previous"))
    }

    public func backup(fingerprint: String) -> Data? {
        try? Data(contentsOf: url(fingerprint, "previous"))
    }

    public func keepUnsaved(_ data: Data, fingerprint: String) -> Bool {
        write(data, to: url(fingerprint, "unsaved"))
    }

    public func unsaved(fingerprint: String) -> Data? {
        try? Data(contentsOf: url(fingerprint, "unsaved"))
    }

    public func removeUnsaved(fingerprint: String) {
        try? FileManager.default.removeItem(at: url(fingerprint, "unsaved"))
    }

    private func url(_ fingerprint: String, _ kind: String) -> URL {
        directory.appending(path: "\(fingerprint.prefix(16))-\(kind).kdbx")
    }

    private func write(_ data: Data, to url: URL) -> Bool {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            #if os(iOS)
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            #else
            try data.write(to: url, options: [.atomic])
            #endif
            var excluded = URLResourceValues()
            excluded.isExcludedFromBackup = true
            var target = url
            try target.setResourceValues(excluded)
            return true
        } catch {
            return false
        }
    }
}
