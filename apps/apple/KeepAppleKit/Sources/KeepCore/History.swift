import Foundation
import KeepCoreBindings

public enum ChangedField: Equatable, Sendable {
    case title
    case userName
    case password
    case url
    case notes
    case customFields
    case tags
    case favorite
    case attachments
    case expiry
}

/// An earlier version of an entry; `differs` is what it has different from the entry as it is now.
public struct HistoryVersion: Equatable, Identifiable, Sendable, CustomStringConvertible {
    public let index: Int
    public let modifiedAt: Int64?
    public let title: EntryText
    public let username: EntryText
    public let differs: [ChangedField]

    public var id: Int { index }

    public init(index: Int, modifiedAt: Int64?, title: EntryText, username: EntryText, differs: [ChangedField]) {
        self.index = index
        self.modifiedAt = modifiedAt
        self.title = title
        self.username = username
        self.differs = differs
    }

    public var description: String { "HistoryVersion(index: \(index))" }
}

public struct VaultHistoryError: Error, Equatable, Sendable {
    public enum Reason: Equatable, Sendable {
        case sessionClosed
        case entryNotFound
        case fieldNotFound
        case versionNotFound
        case notEntitled
    }

    public let reason: Reason

    public init(reason: Reason) {
        self.reason = reason
    }
}

extension KeepVaultSession {
    /// Earlier versions, newest first. Reading them and going back to one need Keep+; the core checks `access`.
    public func history(of id: EntryID, access: KeepAccess) throws(VaultHistoryError) -> [HistoryVersion] {
        try history { try $0.history(entryId: id.rawValue, access: access.dto) }.map {
            HistoryVersion(
                index: Int($0.index),
                modifiedAt: $0.modifiedAt,
                title: $0.title.text,
                username: $0.username.text,
                differs: $0.differs.map(\.field)
            )
        }
    }

    public func historyEntry(_ index: Int, of id: EntryID, access: KeepAccess) throws(VaultHistoryError) -> EntryDetail {
        try history { try $0.historyEntry(entryId: id.rawValue, index: UInt32(index), access: access.dto) }.detail
    }

    public func revealHistory(_ field: SecretField, version index: Int, of id: EntryID, access: KeepAccess) throws(VaultHistoryError) -> SecretValue {
        SecretValue(utf8: try history {
            try $0.revealHistory(entryId: id.rawValue, index: UInt32(index), field: field.dto, access: access.dto)
        }.utf8)
    }

    private func history<T>(_ body: (VaultSessionHandle) throws -> T) throws(VaultHistoryError) -> T {
        if isClosed { throw VaultHistoryError(reason: .sessionClosed) }
        do {
            return try body(handleForEditing)
        } catch let error as KeepCoreBindings.VaultHistoryError {
            throw VaultHistoryError(reason: error.reason)
        } catch {
            throw VaultHistoryError(reason: .sessionClosed)
        }
    }
}

private extension KeepCoreBindings.VaultHistoryError {
    var reason: VaultHistoryError.Reason {
        switch self {
        case .SessionNotFound, .SessionClosed: .sessionClosed
        case .EntryNotFound, .InvalidArgument: .entryNotFound
        case .FieldNotFound: .fieldNotFound
        case .VersionNotFound: .versionNotFound
        case .NotEntitled: .notEntitled
        }
    }
}

private extension ChangedFieldDto {
    var field: ChangedField {
        switch self {
        case .title: .title
        case .userName: .userName
        case .password: .password
        case .url: .url
        case .notes: .notes
        case .customFields: .customFields
        case .tags: .tags
        case .favorite: .favorite
        case .attachments: .attachments
        case .expiry: .expiry
        }
    }
}
