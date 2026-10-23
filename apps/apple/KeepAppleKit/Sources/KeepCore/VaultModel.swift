import Foundation

public struct EntryID: Hashable, Sendable, CustomStringConvertible {
    public let rawValue: String
    public init(_ rawValue: String) { self.rawValue = rawValue }
    public var description: String { rawValue }
}

public struct GroupID: Hashable, Sendable, CustomStringConvertible {
    public let rawValue: String
    public init(_ rawValue: String) { self.rawValue = rawValue }
    public var description: String { rawValue }
}

public enum EntryText: Equatable, Sendable {
    case plain(String)
    case hidden
}

public enum SecretField: Equatable, Sendable {
    case password, title, userName, url, notes
    case custom(String)
}

public struct VaultInfo: Equatable, Sendable {
    public let name: String
    public let formatMajor: Int
    public let formatMinor: Int
    public let rootGroupID: GroupID
    public let groupCount: Int
    public let entryCount: Int
    public let fingerprint: String
    /// Set when the vault has its recycle bin enabled and the group exists.
    public let recycleBinID: GroupID?

    public init(name: String, formatMajor: Int, formatMinor: Int, rootGroupID: GroupID, groupCount: Int, entryCount: Int, fingerprint: String = "", recycleBinID: GroupID? = nil) {
        self.recycleBinID = recycleBinID
        self.name = name
        self.formatMajor = formatMajor
        self.formatMinor = formatMinor
        self.rootGroupID = rootGroupID
        self.groupCount = groupCount
        self.entryCount = entryCount
        self.fingerprint = fingerprint
    }
}

public struct VaultGroup: Equatable, Sendable {
    public let id: GroupID
    public let parentID: GroupID?
    public let name: String
    /// A standard KeePass icon number; 48 is the plain folder.
    public let icon: Int

    public init(id: GroupID, parentID: GroupID?, name: String, icon: Int = 48) {
        self.id = id
        self.parentID = parentID
        self.name = name
        self.icon = icon
    }
}

public struct CustomFieldInfo: Equatable, Sendable {
    public let name: String
    public let isProtected: Bool

    public init(name: String, isProtected: Bool) {
        self.name = name
        self.isProtected = isProtected
    }
}

public struct EntrySummary: Equatable, Sendable {
    public let id: EntryID
    public let groupID: GroupID
    public let title: EntryText
    public let username: EntryText
    public let url: EntryText
    public let tags: [String]
    public let hasPassword: Bool
    public let hasNotes: Bool
    public let hasProtectedFields: Bool
    public let customFields: [CustomFieldInfo]
    public let hasTotp: Bool
    public let favorite: Bool
    /// Unix seconds; `nil` when the entry never expires.
    public let expiresAt: Int64?
    /// A standard KeePass icon number; 0, the key, is what an entry has by default.
    public let icon: Int
    /// The id of an image kept in the file that stands in for the standard icon.
    public let customIcon: String?
    /// The public side of the passkey the entry holds, if it holds one.
    public let passkey: PasskeyInfo?

    public init(
        id: EntryID, groupID: GroupID, title: EntryText, username: EntryText, url: EntryText, tags: [String] = [],
        hasPassword: Bool, hasNotes: Bool = false, hasProtectedFields: Bool = false, customFields: [CustomFieldInfo] = [],
        hasTotp: Bool = false, favorite: Bool = false, expiresAt: Int64? = nil, icon: Int = 0,
        customIcon: String? = nil, passkey: PasskeyInfo? = nil
    ) {
        self.icon = icon
        self.customIcon = customIcon
        self.passkey = passkey
        self.id = id
        self.groupID = groupID
        self.title = title
        self.username = username
        self.url = url
        self.tags = tags
        self.hasPassword = hasPassword
        self.hasNotes = hasNotes
        self.hasProtectedFields = hasProtectedFields
        self.customFields = customFields
        self.hasTotp = hasTotp
        self.favorite = favorite
        self.expiresAt = expiresAt
    }

    public func isExpired(at now: Date = Date()) -> Bool {
        guard let expiresAt else { return false }
        return expiresAt <= Int64(now.timeIntervalSince1970)
    }
}

public struct CustomField: Equatable, Sendable {
    public let name: String
    public let value: EntryText

    public init(name: String, value: EntryText) {
        self.name = name
        self.value = value
    }
}

public struct AttachmentInfo: Equatable, Hashable, Sendable {
    public let name: String
    public let size: Int64

    public init(name: String, size: Int64) {
        self.name = name
        self.size = size
    }
}

/// Larger attachments are refused by the core.
public let maxAttachmentBytes: Int64 = 64 * 1024 * 1024

public struct EntryDetail: Equatable, Sendable {
    public let summary: EntrySummary
    public let notes: EntryText
    public let customFields: [CustomField]
    public let attachments: [AttachmentInfo]
    public let modifiedAt: Int64?
    /// How many earlier versions the entry keeps. Reading them needs Keep+; the count does not.
    public let historyCount: Int

    public init(
        summary: EntrySummary,
        notes: EntryText,
        customFields: [CustomField],
        attachments: [AttachmentInfo] = [],
        modifiedAt: Int64? = nil,
        historyCount: Int = 0
    ) {
        self.summary = summary
        self.notes = notes
        self.customFields = customFields
        self.attachments = attachments
        self.modifiedAt = modifiedAt
        self.historyCount = historyCount
    }
}

public struct VaultOpenError: Error, Equatable, Sendable {
    public enum Reason: Sendable {
        case notAVault, unsupported, missingCredentials, invalidCredentials, invalidKeyFile, damaged, tooLarge, tooManySessions, unavailable
    }

    public let reason: Reason

    public init(reason: Reason) { self.reason = reason }
}

public struct VaultSessionError: Error, Equatable, Sendable {
    public enum Reason: Sendable {
        case sessionNotFound, sessionClosed, entryNotFound, fieldNotFound, invalidArgument
    }

    public let reason: Reason

    public init(reason: Reason) { self.reason = reason }
}

protocol RedactedDescription: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    var redactedDescription: String { get }
}

extension RedactedDescription {
    public var description: String { redactedDescription }
    public var debugDescription: String { redactedDescription }
    public var customMirror: Mirror { Mirror(reflecting: redactedDescription) }
}

extension EntryText: RedactedDescription {
    var redactedDescription: String {
        switch self {
        case .plain: "plain(<redacted>)"
        case .hidden: "hidden"
        }
    }
}

extension SecretField: RedactedDescription {
    var redactedDescription: String {
        if case .custom = self { return "custom(<name>)" }
        return "\(Mirror(reflecting: self).children.first?.label ?? "field")"
    }
}

extension VaultInfo: RedactedDescription {
    var redactedDescription: String { "VaultInfo(format: \(formatMajor).\(formatMinor), entries: \(entryCount))" }
}

extension VaultGroup: RedactedDescription {
    var redactedDescription: String { "VaultGroup(id: \(id))" }
}

extension CustomFieldInfo: RedactedDescription {
    var redactedDescription: String { "CustomFieldInfo(protected: \(isProtected))" }
}

extension EntrySummary: RedactedDescription {
    var redactedDescription: String { "EntrySummary(id: \(id))" }
}

extension CustomField: RedactedDescription {
    var redactedDescription: String { "CustomField(value: \(value))" }
}

extension EntryDetail: RedactedDescription {
    var redactedDescription: String { "EntryDetail(id: \(summary.id))" }
}

/// What an entry's passkey is for. The identifiers are base64url, as the site knows them; nothing here signs.
public struct PasskeyInfo: Equatable, Hashable, Sendable {
    public let rpID: String
    public let userName: String
    public let credentialID: String
    public let userHandle: String

    public init(rpID: String, userName: String, credentialID: String, userHandle: String) {
        self.rpID = rpID
        self.userName = userName
        self.credentialID = credentialID
        self.userHandle = userHandle
    }

    public var credentialIDData: Data? { Self.decode(credentialID) }
    public var userHandleData: Data? { Self.decode(userHandle) }

    public static func decode(_ text: String) -> Data? {
        var base64 = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64.append("=") }
        return Data(base64Encoded: base64)
    }

    public static func encode(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

/// A passkey that was made and saved: the entry it is in, and what the site gets back.
public struct CreatedPasskey: Equatable, Sendable {
    public let entryID: EntryID
    public let credentialID: Data
    public let attestationObject: Data

    public init(entryID: EntryID, credentialID: Data, attestationObject: Data) {
        self.entryID = entryID
        self.credentialID = credentialID
        self.attestationObject = attestationObject
    }
}

public struct PasskeyAssertion: Equatable, Sendable {
    public let credentialID: Data
    public let userHandle: Data
    public let authenticatorData: Data
    public let signature: Data

    public init(credentialID: Data, userHandle: Data, authenticatorData: Data, signature: Data) {
        self.credentialID = credentialID
        self.userHandle = userHandle
        self.authenticatorData = authenticatorData
        self.signature = signature
    }
}

public struct VaultPasskeyError: Error, Equatable, Sendable {
    public enum Reason: Equatable, Sendable {
        case sessionClosed
        case invalidRequest
        case noPasskey
        case invalidKey
        case unavailable
    }

    public let reason: Reason

    public init(reason: Reason) {
        self.reason = reason
    }
}
