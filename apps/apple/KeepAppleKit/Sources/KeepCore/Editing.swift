import Foundation
import KeepCoreBindings

/// One custom field as edited. `value` is UTF-8; the core turns it into a zeroizing string.
public struct CustomFieldDraft: Equatable, Sendable, CustomStringConvertible {
    public var name: String
    public var value: Data
    public var isProtected: Bool

    public init(name: String, value: Data, isProtected: Bool) {
        self.name = name
        self.value = value
        self.isProtected = isProtected
    }

    public var description: String { "CustomFieldDraft(<redacted>, protected: \(isProtected))" }
}

/// The editable part of an entry. Everything else the file holds (history, attachments, icon,
/// auto-type, custom data) is kept by the core as it is.
public struct EntryDraft: Equatable, Sendable, CustomStringConvertible {
    public var title: String
    public var username: String
    public var password: Data
    public var url: String
    public var notes: String
    public var customFields: [CustomFieldDraft]
    public var tags: [String]
    public var favorite: Bool
    public var expiresAt: Int64?

    public init(
        title: String = "", username: String = "", password: Data = Data(), url: String = "", notes: String = "",
        customFields: [CustomFieldDraft] = [], tags: [String] = [], favorite: Bool = false, expiresAt: Int64? = nil
    ) {
        self.title = title
        self.username = username
        self.password = password
        self.url = url
        self.notes = notes
        self.customFields = customFields
        self.tags = tags
        self.favorite = favorite
        self.expiresAt = expiresAt
    }

    public var description: String { "EntryDraft(<redacted>, customFields: \(customFields.count))" }

    /// Best effort: `Data` is copy-on-write, so only this copy is cleared.
    public mutating func wipe() {
        password.resetBytes(in: 0..<password.count)
        for index in customFields.indices {
            customFields[index].value.resetBytes(in: 0..<customFields[index].value.count)
        }
    }

    var dto: EntryDraftDto {
        EntryDraftDto(
            title: title,
            username: username,
            password: password,
            url: url,
            notes: notes,
            customFields: customFields.map { CustomFieldDraftDto(name: $0.name, value: $0.value, protected: $0.isProtected) },
            tags: tags,
            favorite: favorite,
            expiresAt: expiresAt
        )
    }
}

public enum DeleteOutcome: Equatable, Sendable {
    case movedToRecycleBin
    case deleted
}

public struct VaultEditError: Error, Equatable, Sendable {
    public enum Reason: Equatable, Sendable {
        case readOnly
        case entryNotFound
        case groupNotFound
        case groupNotEmpty
        case rootGroup
        case invalidFieldName
        case invalidText
        case sessionClosed
        case unavailable
        case attachmentNotFound
        case attachmentTooLarge
        case notEntitled
        case versionNotFound
    }

    public let reason: Reason

    public init(reason: Reason) {
        self.reason = reason
    }
}

public struct VaultSaveError: Error, Equatable, Sendable {
    public enum Reason: Equatable, Sendable {
        case readOnly
        case encryptionFailed
        /// The encrypted bytes did not read back as the same vault; nothing may be written.
        case verificationFailed
        case randomUnavailable
        case sessionClosed
    }

    public let reason: Reason

    public init(reason: Reason) {
        self.reason = reason
    }
}

/// Encrypted, verified KDBX bytes and the edit generation they contain.
public struct SaveSnapshot: Sendable, CustomStringConvertible {
    public let bytes: Data
    public let generation: UInt64

    public init(bytes: Data, generation: UInt64) {
        self.bytes = bytes
        self.generation = generation
    }

    public var description: String { "SaveSnapshot(\(bytes.count) bytes, generation: \(generation))" }
}

public struct PasswordOptions: Equatable, Sendable {
    public static let lengthRange = 4...128

    public var length: Int
    public var lowercase: Bool
    public var uppercase: Bool
    public var digits: Bool
    public var symbols: Bool
    public var excludeAmbiguous: Bool

    public init(length: Int = 20, lowercase: Bool = true, uppercase: Bool = true, digits: Bool = true, symbols: Bool = true, excludeAmbiguous: Bool = false) {
        self.length = length
        self.lowercase = lowercase
        self.uppercase = uppercase
        self.digits = digits
        self.symbols = symbols
        self.excludeAmbiguous = excludeAmbiguous
    }

    public var hasCharacterSet: Bool { lowercase || uppercase || digits || symbols }
}

public enum PasswordGenerator {
    public enum Failure: Error, Equatable, Sendable {
        case noCharacterSets
        case lengthOutOfRange
        case randomUnavailable
    }

    /// From the core's CSPRNG generator, shared with every client.
    public static func generate(_ options: PasswordOptions) throws(Failure) -> SecretValue {
        let dto = PasswordOptionsDto(
            length: UInt32(clamping: options.length),
            lowercase: options.lowercase,
            uppercase: options.uppercase,
            digits: options.digits,
            symbols: options.symbols,
            excludeAmbiguous: options.excludeAmbiguous
        )
        do {
            return SecretValue(utf8: try generatePassword(options: dto).utf8)
        } catch let error as PasswordGeneratorError {
            switch error {
            case .NoCharacterSets: throw .noCharacterSets
            case .LengthOutOfRange: throw .lengthOutOfRange
            case .RandomUnavailable: throw .randomUnavailable
            }
        } catch {
            throw .randomUnavailable
        }
    }
}

extension KeepVaultSession {
    public func isEditable() throws(VaultSessionError) -> Bool {
        try call { try $0.isEditable() }
    }

    public func isDirty() throws(VaultSessionError) -> Bool {
        try call { try $0.isDirty() }
    }

    public func createEntry(in group: GroupID, _ draft: EntryDraft) throws(VaultEditError) -> EntryID {
        EntryID(try edit { try $0.createEntry(groupId: group.rawValue, draft: draft.dto) })
    }

    public func updateEntry(_ id: EntryID, _ draft: EntryDraft) throws(VaultEditError) {
        try edit { try $0.updateEntry(entryId: id.rawValue, draft: draft.dto) }
    }

    public func deleteEntry(_ id: EntryID, permanent: Bool) throws(VaultEditError) -> DeleteOutcome {
        try edit { try $0.deleteEntry(entryId: id.rawValue, permanent: permanent) }.outcome
    }

    /// Returns the name it was stored under. Adding needs Keep+; the core checks `access`.
    public func addAttachment(_ name: String, data: Data, to id: EntryID, access: KeepAccess) throws(VaultEditError) -> String {
        try edit { try $0.addAttachment(entryId: id.rawValue, name: name, data: data, access: access.dto) }
    }

    public func removeAttachment(_ name: String, from id: EntryID) throws(VaultEditError) {
        try edit { try $0.removeAttachment(entryId: id.rawValue, name: name) }
    }

    /// Makes an earlier version current again; the entry as it is now goes to the history first.
    public func restoreHistory(_ index: Int, of id: EntryID, access: KeepAccess) throws(VaultEditError) {
        try edit { try $0.restoreHistory(entryId: id.rawValue, index: UInt32(index), access: access.dto) }
    }

    /// Takes every entry of the export into `group`; the export's folders become groups.
    public func importPlan(_ plan: ImportPlan, into group: GroupID) throws(VaultEditError) -> ImportOutcome {
        guard let handle = plan.handle else { throw VaultEditError(reason: .unavailable) }
        let dto = try edit { try $0.importPlan(groupId: group.rawValue, plan: handle) }
        return ImportOutcome(entries: Int(dto.entries), groups: Int(dto.groups))
    }

    public func setFavorite(_ id: EntryID, _ favorite: Bool) throws(VaultEditError) {
        try edit { try $0.setFavorite(entryId: id.rawValue, favorite: favorite) }
    }

    public func moveEntry(_ id: EntryID, to group: GroupID) throws(VaultEditError) {
        try edit { try $0.moveEntry(entryId: id.rawValue, groupId: group.rawValue) }
    }

    public func createGroup(in parent: GroupID, name: String) throws(VaultEditError) -> GroupID {
        GroupID(try edit { try $0.createGroup(parentId: parent.rawValue, name: name) })
    }

    /// One of the standard KeePass icons (0...68); other apps show their own drawing of it.
    public func setGroupIcon(_ id: GroupID, _ icon: Int) throws(VaultEditError) {
        try edit { try $0.setGroupIcon(groupId: id.rawValue, icon: UInt32(max(0, icon))) }
    }

    /// Gives every entry that still has the default icon one by its address, offline; returns how many.
    public func assignIconsByURL() throws(VaultEditError) -> Int {
        Int(try edit { try $0.assignIconsByUrl() })
    }

    /// Keeps the fetched icons (PNG) in the file and shows them on the entries of their sites; returns how many entries got one.
    public func setSiteIcons(_ icons: [SiteIcon]) throws(VaultEditError) -> Int {
        Int(try edit { try $0.setSiteIcons(icons: icons.map { SiteIconDto(host: $0.host, png: $0.png) }) })
    }

    public func setEntryIcon(_ id: EntryID, _ icon: Int) throws(VaultEditError) {
        try edit { try $0.setEntryIcon(entryId: id.rawValue, icon: UInt32(max(0, icon))) }
    }

    public func renameGroup(_ id: GroupID, name: String) throws(VaultEditError) {
        try edit { try $0.renameGroup(groupId: id.rawValue, name: name) }
    }

    public func deleteGroup(_ id: GroupID, recursive: Bool, permanent: Bool) throws(VaultEditError) -> DeleteOutcome {
        try edit { try $0.deleteGroup(groupId: id.rawValue, recursive: recursive, permanent: permanent) }.outcome
    }

    public func prepareSave() throws(VaultSaveError) -> SaveSnapshot {
        if isClosed { throw VaultSaveError(reason: .sessionClosed) }
        do {
            let dto = try handleForEditing.prepareSave()
            return SaveSnapshot(bytes: dto.bytes, generation: dto.generation)
        } catch let error as KeepCoreBindings.VaultSaveError {
            switch error {
            case .ReadOnly: throw VaultSaveError(reason: .readOnly)
            case .VerificationFailed: throw VaultSaveError(reason: .verificationFailed)
            case .RandomUnavailable: throw VaultSaveError(reason: .randomUnavailable)
            case .SessionClosed, .SessionNotFound: throw VaultSaveError(reason: .sessionClosed)
            case .EncryptionFailed: throw VaultSaveError(reason: .encryptionFailed)
            }
        } catch {
            throw VaultSaveError(reason: .encryptionFailed)
        }
    }

    @discardableResult
    public func markSaved(generation: UInt64) throws(VaultSessionError) -> Bool {
        try call { try $0.markSaved(generation: generation) }
    }

    /// Makes a passkey for a site and keeps it in a new entry of the group; the private key stays in the vault.
    public func createPasskey(in group: GroupID, rpID: String, rpName: String, userName: String, userHandle: Data) throws(VaultEditError) -> CreatedPasskey {
        try edit {
            let made = try $0.createPasskey(groupId: group.rawValue, rpId: rpID, rpName: rpName, userName: userName, userHandle: userHandle)
            return CreatedPasskey(entryID: EntryID(made.entryId), credentialID: made.credentialId, attestationObject: made.attestationObject)
        }
    }

    /// Takes the passkey out of an entry; the entry stays.
    public func removePasskey(_ id: EntryID) throws(VaultEditError) {
        try edit { try $0.removePasskey(entryId: id.rawValue) }
    }

    private func edit<T>(_ body: (VaultSessionHandle) throws -> T) throws(VaultEditError) -> T {
        if isClosed { throw VaultEditError(reason: .sessionClosed) }
        do {
            return try body(handleForEditing)
        } catch let error as KeepCoreBindings.VaultEditError {
            throw VaultEditError(reason: error.reason)
        } catch let error as KeepCoreBindings.VaultPasskeyError {
            throw VaultEditError(reason: {
                switch error {
                case .ReadOnly: .readOnly
                case .GroupNotFound: .groupNotFound
                case .SessionNotFound, .SessionClosed: .sessionClosed
                case .InvalidRequest: .invalidText
                case .NoPasskey, .InvalidKey, .Failed: .unavailable
                }
            }())
        } catch {
            throw VaultEditError(reason: .unavailable)
        }
    }
}

private extension KeepCoreBindings.VaultEditError {
    var reason: VaultEditError.Reason {
        switch self {
        case .ReadOnly: .readOnly
        case .EntryNotFound: .entryNotFound
        case .GroupNotFound: .groupNotFound
        case .GroupNotEmpty: .groupNotEmpty
        case .RootGroup: .rootGroup
        case .InvalidFieldName: .invalidFieldName
        case .InvalidText, .InvalidArgument: .invalidText
        case .SessionClosed, .SessionNotFound: .sessionClosed
        case .RandomUnavailable: .unavailable
        case .AttachmentNotFound: .attachmentNotFound
        case .AttachmentTooLarge: .attachmentTooLarge
        case .NotEntitled: .notEntitled
        case .VersionNotFound: .versionNotFound
        }
    }
}

private extension DeleteOutcomeDto {
    var outcome: DeleteOutcome {
        switch self {
        case .movedToRecycleBin: .movedToRecycleBin
        case .deleted: .deleted
        }
    }
}

public struct SiteIcon: Equatable, Sendable {
    public let host: String
    public let png: Data

    public init(host: String, png: Data) {
        self.host = host
        self.png = png
    }
}

/// Where a site's icon may be, best first, judged from the text of its front page.
public func siteIconLinks(host: String, html: String) -> [String] {
    KeepCoreBindings.siteIconLinks(host: host, html: html)
}
