import Foundation
import KeepCoreBindings

/// How a second version of the database is combined into the open one.
public struct MergeOptions: Equatable, Sendable {
    /// Appended to the title of a conflict copy; the app passes one in the person's language.
    public var conflictSuffix: String
    /// Merge a database that is not a version of this one: every item is then new.
    public var allowUnrelated: Bool

    public init(conflictSuffix: String = " (conflict)", allowUnrelated: Bool = false) {
        self.conflictSuffix = conflictSuffix
        self.allowUnrelated = allowUnrelated
    }
}

/// What a merge did: counts and ids only, never a value from the vault.
public struct MergeReport: Equatable, Sendable {
    /// Whether the open database is different now, and so dirty.
    public let changed: Bool
    public let entriesAdded: Int
    /// Entries changed after a deletion here, which therefore came back.
    public let entriesRestored: Int
    /// Entries whose current version is now the other side's.
    public let entriesUpdated: Int
    public let entriesMoved: Int
    public let entriesDeleted: Int
    /// Entries whose current version stayed but whose history changed.
    public let historiesMerged: Int
    public let historyVersionsAdded: Int
    public let groupsAdded: Int
    public let groupsUpdated: Int
    public let groupsMoved: Int
    public let groupsDeleted: Int
    /// The conflict copies made, one per entry changed on both sides.
    public let conflicts: [EntryID]
    public let iconsAdded: Int
    public let deletionRecordsAdded: Int
    public let settingsUpdated: Bool
}

public struct VaultMergeError: Error, Equatable, Sendable {
    public enum Reason: Equatable, Sendable {
        case sessionNotFound
        case sessionClosed
        case readOnly
        /// The two databases are not versions of one vault, and the options did not allow it.
        case differentVault
        /// The merged result would not be a valid vault; nothing changed.
        case invalidResult
        // Opening the other file, as for `openVault`.
        case notAVault
        case unsupported
        case missingCredentials
        case invalidCredentials
        case invalidKeyFile
        case damaged
        case tooLarge
        case unavailable
    }

    public let reason: Reason

    public init(reason: Reason) {
        self.reason = reason
    }
}

extension KeepVaultSession {
    /// Combines another version of this database into the open one (ADR 0023). `data` is the other
    /// file's encrypted bytes, opened with its own credentials; it takes no session. Nothing is
    /// written: the session is dirty if anything changed, and is saved like any edit. A failure
    /// changes nothing.
    public func merge(_ data: Data, credentials: VaultCredentials, options: MergeOptions = MergeOptions()) throws(VaultMergeError) -> MergeReport {
        if isClosed { throw VaultMergeError(reason: .sessionClosed) }
        let password: PasswordDto? = if let material = credentials.unlockMaterial {
            .unlockMaterial(bytes: material)
        } else {
            credentials.password.map { .text(utf8: $0) }
        }
        let dto = VaultCredentialsDto(password: password, keyFile: credentials.keyFile, retainUnlockMaterial: false)
        do {
            let report = try handleForEditing.merge(
                data: data,
                credentials: dto,
                options: MergeOptionsDto(conflictSuffix: options.conflictSuffix, allowUnrelated: options.allowUnrelated)
            )
            return MergeReport(report)
        } catch let error as KeepCoreBindings.VaultMergeError {
            throw VaultMergeError(reason: error.reason)
        } catch {
            throw VaultMergeError(reason: .unavailable)
        }
    }
}

extension MergeReport {
    init(_ dto: MergeReportDto) {
        self.init(
            changed: dto.changed,
            entriesAdded: Int(dto.entriesAdded),
            entriesRestored: Int(dto.entriesRestored),
            entriesUpdated: Int(dto.entriesUpdated),
            entriesMoved: Int(dto.entriesMoved),
            entriesDeleted: Int(dto.entriesDeleted),
            historiesMerged: Int(dto.historiesMerged),
            historyVersionsAdded: Int(dto.historyVersionsAdded),
            groupsAdded: Int(dto.groupsAdded),
            groupsUpdated: Int(dto.groupsUpdated),
            groupsMoved: Int(dto.groupsMoved),
            groupsDeleted: Int(dto.groupsDeleted),
            conflicts: dto.conflicts.map(EntryID.init),
            iconsAdded: Int(dto.iconsAdded),
            deletionRecordsAdded: Int(dto.deletionRecordsAdded),
            settingsUpdated: dto.settingsUpdated
        )
    }
}

extension KeepCoreBindings.VaultMergeError {
    fileprivate var reason: VaultMergeError.Reason {
        switch self {
        case .SessionNotFound: .sessionNotFound
        case .SessionClosed: .sessionClosed
        case .ReadOnly: .readOnly
        case .DifferentVault: .differentVault
        case .InvalidResult: .invalidResult
        case .NotAVault: .notAVault
        case .Unsupported: .unsupported
        case .MissingCredentials: .missingCredentials
        case .InvalidCredentials: .invalidCredentials
        case .InvalidKeyFile: .invalidKeyFile
        case .Damaged: .damaged
        case .TooLarge: .tooLarge
        case .Unavailable: .unavailable
        }
    }
}
