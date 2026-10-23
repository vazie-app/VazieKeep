import Foundation
import KeepCoreBindings
import Synchronization

public final class KeepVaultSession: Sendable, CustomStringConvertible {
    private let handle: VaultSessionHandle
    private let closed = Atomic<Bool>(false)

    init(handle: VaultSessionHandle) {
        self.handle = handle
    }

    deinit {
        close()
    }

    public var isClosed: Bool { closed.load(ordering: .acquiring) }

    var handleForEditing: VaultSessionHandle { handle }

    public func info() throws(VaultSessionError) -> VaultInfo {
        let dto = try call { try $0.info() }
        return VaultInfo(
            name: dto.name,
            formatMajor: Int(dto.formatMajor),
            formatMinor: Int(dto.formatMinor),
            rootGroupID: GroupID(dto.rootGroupId),
            groupCount: Int(dto.groupCount),
            entryCount: Int(dto.entryCount),
            fingerprint: dto.fingerprint,
            recycleBinID: dto.recycleBinId.map(GroupID.init)
        )
    }

    public func groups() throws(VaultSessionError) -> [VaultGroup] {
        try call { try $0.groups() }.map {
            VaultGroup(id: GroupID($0.id), parentID: $0.parentId.map(GroupID.init), name: $0.name, icon: Int($0.icon))
        }
    }

    public func entries() throws(VaultSessionError) -> [EntrySummary] {
        try call { try $0.entries() }.map(\.summary)
    }

    public func search(_ query: String) throws(VaultSessionError) -> [EntrySummary] {
        try call { try $0.search(query: query) }.map(\.summary)
    }

    public func entry(_ id: EntryID) throws(VaultSessionError) -> EntryDetail {
        try call { try $0.entry(entryId: id.rawValue) }.detail
    }

    /// The attachment's content, as a secret: it is vault data.
    /// The entries that hold a passkey, for one site or (nil) for every site. Deleted entries are left out.
    public func passkeys(rpID: String?) throws(VaultSessionError) -> [EntrySummary] {
        try call { try $0.passkeys(rpId: rpID ?? "") }.map(\.summary)
    }

    /// Signs a sign-in request with the entry's passkey, for the site it was made for only.
    public func signPasskey(_ id: EntryID, rpID: String, clientDataHash: Data) throws(VaultPasskeyError) -> PasskeyAssertion {
        if isClosed { throw VaultPasskeyError(reason: .sessionClosed) }
        do {
            let signed = try handle.signPasskey(entryId: id.rawValue, rpId: rpID, clientDataHash: clientDataHash)
            return PasskeyAssertion(credentialID: signed.credentialId, userHandle: signed.userHandle, authenticatorData: signed.authenticatorData, signature: signed.signature)
        } catch let error as KeepCoreBindings.VaultPasskeyError {
            throw VaultPasskeyError(reason: {
                switch error {
                case .SessionNotFound, .SessionClosed: .sessionClosed
                case .InvalidRequest, .GroupNotFound, .ReadOnly: .invalidRequest
                case .NoPasskey: .noPasskey
                case .InvalidKey: .invalidKey
                case .Failed: .unavailable
                }
            }())
        } catch {
            throw VaultPasskeyError(reason: .unavailable)
        }
    }

    /// The sites whose icons are worth fetching: entries with an address and no image of their own.
    public func siteIconHosts() throws(VaultSessionError) -> [String] {
        try call { try $0.siteIconHosts() }
    }

    /// The site to ask for one entry's icon, when it has an address and no image yet.
    public func siteIconHost(for id: EntryID) throws(VaultSessionError) -> String? {
        try call { try $0.siteIconHost(entryId: id.rawValue) }
    }

    /// The bytes of an image kept in the file, by the id an entry names.
    public func customIcon(_ id: String) throws(VaultSessionError) -> Data? {
        try call { try $0.customIcon(iconId: id) }
    }

    public func attachment(_ name: String, for id: EntryID) throws(VaultSessionError) -> SecretValue {
        SecretValue(utf8: try call { try $0.attachment(entryId: id.rawValue, name: name) }.utf8)
    }

    public func reveal(_ field: SecretField, for id: EntryID) throws(VaultSessionError) -> SecretValue {
        SecretValue(utf8: try call { try $0.reveal(entryId: id.rawValue, field: field.dto) }.utf8)
    }

    public func password(for id: EntryID) throws(VaultSessionError) -> SecretValue {
        try reveal(.password, for: id)
    }

    /// The retained password component, or nil when the vault wasn't opened with a typed password
    /// or retention wasn't requested.
    public func unlockMaterial() throws(VaultSessionError) -> SecretValue? {
        try call { try $0.unlockMaterial() }.map { SecretValue(utf8: $0.bytes) }
    }

    public func close() {
        if closed.exchange(true, ordering: .acquiringAndReleasing) == false {
            _ = handle.closeSession()
        }
    }

    public var description: String { "KeepVaultSession(<opaque>)" }

    func call<T>(_ body: (VaultSessionHandle) throws -> T) throws(VaultSessionError) -> T {
        if isClosed { throw VaultSessionError(reason: .sessionClosed) }
        do {
            return try body(handle)
        } catch let error as KeepCoreBindings.VaultSessionError {
            throw VaultSessionError(reason: error.reason)
        } catch {
            throw VaultSessionError(reason: .sessionNotFound)
        }
    }
}

extension KeepCoreBindings.VaultSessionError {
    var reason: VaultSessionError.Reason {
        switch self {
        case .SessionNotFound: .sessionNotFound
        case .SessionClosed: .sessionClosed
        case .EntryNotFound: .entryNotFound
        case .FieldNotFound: .fieldNotFound
        case .InvalidArgument: .invalidArgument
        }
    }
}

extension OpenVaultError {
    var reason: VaultOpenError.Reason {
        switch self {
        case .NotAVault: .notAVault
        case .Unsupported: .unsupported
        case .MissingCredentials: .missingCredentials
        case .InvalidCredentials: .invalidCredentials
        case .InvalidKeyFile: .invalidKeyFile
        case .Damaged: .damaged
        case .TooLarge: .tooLarge
        case .TooManySessions: .tooManySessions
        case .Unavailable: .unavailable
        }
    }
}

extension EntryDetailDto {
    var detail: EntryDetail {
        EntryDetail(
            summary: summary.summary,
            notes: notes.text,
            customFields: customFields.map { CustomField(name: $0.name, value: $0.value.text) },
            attachments: attachments.map { AttachmentInfo(name: $0.name, size: Int64($0.size)) },
            modifiedAt: modifiedAt,
            historyCount: Int(historyCount)
        )
    }
}

extension TextDto {
    var text: EntryText {
        switch self {
        case let .plain(value): .plain(value)
        case .protected: .hidden
        }
    }
}

private extension EntrySummaryDto {
    var summary: EntrySummary {
        EntrySummary(
            id: EntryID(id),
            groupID: GroupID(groupId),
            title: title.text,
            username: username.text,
            url: url.text,
            tags: tags,
            hasPassword: hasPassword,
            hasNotes: hasNotes,
            hasProtectedFields: hasProtectedFields,
            customFields: customFields.map { CustomFieldInfo(name: $0.name, isProtected: $0.protected) },
            hasTotp: hasTotp,
            favorite: favorite,
            expiresAt: expiresAt,
            icon: Int(icon),
            customIcon: customIcon,
            passkey: passkey.map { PasskeyInfo(rpID: $0.rpId, userName: $0.userName, credentialID: $0.credentialId, userHandle: $0.userHandle) }
        )
    }
}

extension SecretField {
    var dto: SecretFieldDto {
        switch self {
        case .password: .password
        case .title: .title
        case .userName: .userName
        case .url: .url
        case .notes: .notes
        case let .custom(name): .custom(name: name)
        }
    }
}
