import CryptoKit
import Foundation

/// A file the user picked, kept as a bookmark. Neither the bookmark nor the name is secret; the
/// file's content is never stored.
public struct DocumentReference: Codable, Equatable, Sendable, CustomStringConvertible {
    public var bookmark: Data
    public var displayName: String

    public init(bookmark: Data, displayName: String) {
        self.bookmark = bookmark
        self.displayName = displayName
    }

    public var description: String { "DocumentReference(<bookmark>)" }
}

public enum DocumentError: Error, Equatable, Sendable {
    case unavailable
    case tooLarge
}

/// Which bytes a file held: the SHA-256 of its (encrypted) content.
public struct FileVersion: Equatable, Sendable, CustomStringConvertible {
    public let sha256: Data

    public init(of data: Data) {
        sha256 = Data(SHA256.hash(data: data))
    }

    public var description: String { "FileVersion(\(sha256.prefix(4).map { String(format: "%02x", $0) }.joined()))" }
}

public struct DocumentRead: Sendable {
    public let data: Data
    /// A fresh bookmark when the stored one was stale; the caller persists it.
    public let refreshed: DocumentReference?

    public init(data: Data, refreshed: DocumentReference? = nil) {
        self.data = data
        self.refreshed = refreshed
    }

    public var version: FileVersion { FileVersion(of: data) }
}

public enum WriteFailure: Error, Equatable, Sendable {
    /// The file no longer holds what was opened: someone else saved it. Nothing was written.
    case changedExternally
    /// Moved, deleted, not downloaded, or the bookmark no longer resolves. Nothing was written.
    case unavailable
    /// The location or provider refuses writes. Nothing was written.
    case notWritable
    case insufficientSpace
    /// The previous version couldn't be kept as a backup, so nothing was written.
    case backupFailed
    /// The file didn't read back as written; the previous bytes were put back.
    case verificationFailed
    /// A cloud file whose newest version isn't on this device yet. Nothing was written.
    case outOfDate
}

public struct WriteResult: Sendable {
    public let version: FileVersion
    public let refreshed: DocumentReference?

    public init(version: FileVersion, refreshed: DocumentReference? = nil) {
        self.version = version
        self.refreshed = refreshed
    }
}

public protocol DocumentAccess: Sendable {
    func reference(for url: URL) async throws(DocumentError) -> DocumentReference
    func read(_ reference: DocumentReference, limit: Int) async throws(DocumentError) -> DocumentRead
    func header(_ reference: DocumentReference) async throws(DocumentError) -> Data
    /// Replaces the file with already encrypted bytes, only if it still holds `expected`.
    /// `backup` receives the bytes being replaced before anything is written.
    func replace(_ reference: DocumentReference, with data: Data, expecting expected: FileVersion, backup: @escaping @Sendable (Data) -> Bool) async throws(WriteFailure) -> WriteResult
}

extension DocumentAccess {
    /// Where the file is now. Two references to one file give the same answer.
    public func location(_ reference: DocumentReference) async -> String? { nil }
}

public let maxVaultBytes = 256 * 1024 * 1024
public let maxKeyFileBytes = 64 * 1024 * 1024
let headerBytes = 12

/// Security-scoped bookmarks: access is opened for exactly one coordinated read and closed again.
/// Nothing is copied into the app's container.
public struct SecurityScopedDocumentAccess: DocumentAccess {
    public init() {}

    public func reference(for url: URL) async throws(DocumentError) -> DocumentReference {
        try await detached { () throws(DocumentError) in try Self.makeReference(url) }
    }

    public func read(_ reference: DocumentReference, limit: Int) async throws(DocumentError) -> DocumentRead {
        try await detached { () throws(DocumentError) in try Self.read(reference, mode: .whole(limit: limit)) }
    }

    public func header(_ reference: DocumentReference) async throws(DocumentError) -> Data {
        try await detached { () throws(DocumentError) in try Self.read(reference, mode: .prefix(headerBytes)).data }
    }

    public func location(_ reference: DocumentReference) async -> String? {
        await Task.detached {
            var stale = false
            let url = try? URL(resolvingBookmarkData: reference.bookmark, options: Self.resolutionOptions, relativeTo: nil, bookmarkDataIsStale: &stale)
            return url?.standardizedFileURL.resolvingSymlinksInPath().path
        }.value
    }

    public func replace(_ reference: DocumentReference, with data: Data, expecting expected: FileVersion, backup: @escaping @Sendable (Data) -> Bool) async throws(WriteFailure) -> WriteResult {
        let result = await Task.detached(priority: .userInitiated) { () -> Result<WriteResult, WriteFailure> in
            do throws(WriteFailure) {
                return .success(try Self.replace(reference, with: data, expecting: expected, backup: backup))
            } catch {
                return .failure(error)
            }
        }.value
        return try result.get()
    }

    private enum ReadMode {
        case whole(limit: Int)
        case prefix(Int)
    }

    private func detached<T: Sendable>(_ body: @escaping @Sendable () throws(DocumentError) -> T) async throws(DocumentError) -> T {
        let result = await Task.detached(priority: .userInitiated) { () -> Result<T, DocumentError> in
            do throws(DocumentError) {
                return .success(try body())
            } catch {
                return .failure(error)
            }
        }.value
        return try result.get()
    }

    #if os(macOS)
    private static let creationOptions: URL.BookmarkCreationOptions = [.withSecurityScope]
    private static let resolutionOptions: URL.BookmarkResolutionOptions = [.withSecurityScope, .withoutUI]
    #else
    private static let creationOptions: URL.BookmarkCreationOptions = []
    private static let resolutionOptions: URL.BookmarkResolutionOptions = []
    #endif

    private static func makeReference(_ url: URL) throws(DocumentError) -> DocumentReference {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let bookmark = try url.bookmarkData(options: creationOptions, includingResourceValuesForKeys: nil, relativeTo: nil)
            return DocumentReference(bookmark: bookmark, displayName: displayName(of: url))
        } catch {
            throw .unavailable
        }
    }

    private static func read(_ reference: DocumentReference, mode: ReadMode) throws(DocumentError) -> DocumentRead {
        var stale = false
        let url: URL
        do {
            url = try URL(resolvingBookmarkData: reference.bookmark, options: resolutionOptions, relativeTo: nil, bookmarkDataIsStale: &stale)
        } catch {
            throw .unavailable
        }
        // Files inside the app's own container return false here and need no scope.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        var outcome: Result<Data, DocumentError> = .failure(.unavailable)
        var coordinationError: NSError?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [.withoutChanges], error: &coordinationError) { readable in
            outcome = readBounded(readable, mode: mode)
        }
        if coordinationError != nil { throw .unavailable }
        let data = try outcome.get()

        var refreshed: DocumentReference?
        if stale, let bookmark = try? url.bookmarkData(options: creationOptions, includingResourceValuesForKeys: nil, relativeTo: nil) {
            refreshed = DocumentReference(bookmark: bookmark, displayName: displayName(of: url))
        }
        return DocumentRead(data: data, refreshed: refreshed)
    }

    /// One coordinated write: check the file is still the one that was opened, keep the old bytes,
    /// replace atomically, read back. The original is never overwritten in place.
    private static func replace(_ reference: DocumentReference, with data: Data, expecting expected: FileVersion, backup: @Sendable (Data) -> Bool) throws(WriteFailure) -> WriteResult {
        var stale = false
        let url: URL
        do {
            url = try URL(resolvingBookmarkData: reference.bookmark, options: resolutionOptions, relativeTo: nil, bookmarkDataIsStale: &stale)
        } catch {
            throw .unavailable
        }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        try ensureLatestCloudVersion(url)

        var outcome: Result<Void, WriteFailure> = .failure(.unavailable)
        var coordinationError: NSError?
        NSFileCoordinator().coordinate(writingItemAt: url, options: [.forReplacing], error: &coordinationError) { target in
            outcome = writeChecked(data, to: target, expected: expected, backup: backup)
        }
        if coordinationError != nil { throw .unavailable }
        try outcome.get()

        var refreshed: DocumentReference?
        if let bookmark = try? url.bookmarkData(options: creationOptions, includingResourceValuesForKeys: nil, relativeTo: nil), bookmark != reference.bookmark || stale {
            refreshed = DocumentReference(bookmark: bookmark, displayName: displayName(of: url))
        }
        return WriteResult(version: FileVersion(of: data), refreshed: refreshed)
    }

    /// A coordinated write hands over the local copy of an iCloud file, which can be older than
    /// what another device saved. Only write when iCloud says this copy is the newest one;
    /// otherwise ask for the download and wait a little, then give up without writing.
    private static func ensureLatestCloudVersion(_ url: URL, timeout: Duration = .seconds(15)) throws(WriteFailure) {
        let keys: Set<URLResourceKey> = [.isUbiquitousItemKey, .ubiquitousItemDownloadingStatusKey, .ubiquitousItemHasUnresolvedConflictsKey]
        func status() -> URLResourceValues? {
            var fresh = url
            fresh.removeAllCachedResourceValues()
            return try? fresh.resourceValues(forKeys: keys)
        }
        guard let first = status(), first.isUbiquitousItem == true else { return }
        if first.ubiquitousItemHasUnresolvedConflicts == true { throw .changedExternally }
        if first.ubiquitousItemDownloadingStatus == .current { return }
        try? FileManager.default.startDownloadingUbiquitousItem(at: url)
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            Thread.sleep(forTimeInterval: 0.25)
            guard let values = status() else { continue }
            if values.ubiquitousItemHasUnresolvedConflicts == true { throw .changedExternally }
            if values.ubiquitousItemDownloadingStatus == .current { return }
        }
        throw .outOfDate
    }

    private static func writeChecked(_ data: Data, to target: URL, expected: FileVersion, backup: (Data) -> Bool) -> Result<Void, WriteFailure> {
        guard let current = try? Data(contentsOf: target) else { return .failure(.unavailable) }
        guard FileVersion(of: current) == expected else { return .failure(.changedExternally) }
        guard backup(current) else { return .failure(.backupFailed) }
        if let failure = atomicWrite(data, to: target) { return .failure(failure) }
        guard (try? Data(contentsOf: target)) == data else {
            _ = atomicWrite(current, to: target)
            return .failure(.verificationFailed)
        }
        return .success(())
    }

    /// Writes next to the target and renames over it. Providers that don't allow creating a
    /// sibling get the bytes staged in a replacement directory instead. Never writes in place.
    private static func atomicWrite(_ data: Data, to target: URL) -> WriteFailure? {
        do {
            try data.write(to: target, options: [.atomic])
            return nil
        } catch {
            let first = failure(for: error)
            guard first == .notWritable else { return first }
        }
        let manager = FileManager.default
        do {
            let directory = try manager.url(for: .itemReplacementDirectory, in: .userDomainMask, appropriateFor: target, create: true)
            defer { try? manager.removeItem(at: directory) }
            let staged = directory.appending(path: target.lastPathComponent)
            try data.write(to: staged)
            _ = try manager.replaceItemAt(target, withItemAt: staged, backupItemName: nil, options: [.usingNewMetadataOnly])
            return nil
        } catch {
            return failure(for: error)
        }
    }

    private static func failure(for error: Error) -> WriteFailure {
        let error = error as NSError
        if error.domain == NSPOSIXErrorDomain, error.code == Int(ENOSPC) { return .insufficientSpace }
        if let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError, underlying.domain == NSPOSIXErrorDomain, underlying.code == Int(ENOSPC) {
            return .insufficientSpace
        }
        switch error.code {
        case NSFileWriteOutOfSpaceError: return .insufficientSpace
        case NSFileWriteNoPermissionError, NSFileWriteVolumeReadOnlyError, NSFileReadNoPermissionError: return .notWritable
        default: return .unavailable
        }
    }

    private static func readBounded(_ url: URL, mode: ReadMode) -> Result<Data, DocumentError> {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return .failure(.unavailable) }
        defer { try? handle.close() }
        do {
            switch mode {
            case let .prefix(count):
                return .success(try handle.read(upToCount: count) ?? Data())
            case let .whole(limit):
                if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > limit {
                    return .failure(.tooLarge)
                }
                let data = try handle.read(upToCount: limit + 1) ?? Data()
                return data.count > limit ? .failure(.tooLarge) : .success(data)
            }
        } catch {
            return .failure(.unavailable)
        }
    }

    private static func displayName(of url: URL) -> String {
        (try? url.resourceValues(forKeys: [.localizedNameKey]).localizedName) ?? url.lastPathComponent
    }
}
