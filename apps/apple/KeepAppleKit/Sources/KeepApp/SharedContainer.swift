import Foundation

/// Where the app and the AutoFill extension keep their shared, non-secret state: the vault and
/// key-file bookmarks, the Face ID setup record and the installation id. Never vault content.
public enum SharedContainer {
    public static let appGroup = "group.app.vazie.keep"
    static let sharedFiles = ["vault-reference.json", "face-id-unlock.json", "installation-id"]

    /// The App Group container, or this process's Application Support when the build has no App
    /// Group entitlement (a free Personal Team can't sign one; the extension can't run there).
    public static func directory() -> URL {
        if let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) {
            return group.appending(path: "Library/Application Support", directoryHint: .isDirectory)
        }
        return applicationSupport()
    }

    public static var isShared: Bool {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) != nil
    }

    /// Moves files written by earlier versions from the app's own Application Support into the
    /// shared container, once. Called by the app only, before any store reads them.
    public static func migrateFromApplicationSupport() {
        let target = directory()
        let source = applicationSupport()
        guard target.standardizedFileURL != source.standardizedFileURL else { return }
        try? FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
        for name in sharedFiles {
            let old = source.appending(path: name)
            let new = target.appending(path: name)
            guard FileManager.default.fileExists(atPath: old.path), !FileManager.default.fileExists(atPath: new.path) else { continue }
            try? FileManager.default.moveItem(at: old, to: new)
        }
    }

    private static func applicationSupport() -> URL {
        (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
    }
}
