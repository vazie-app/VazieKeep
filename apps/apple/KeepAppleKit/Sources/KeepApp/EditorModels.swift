import Foundation
import KeepCore
import Observation

public struct EditableField: Identifiable, Equatable, Sendable, CustomStringConvertible {
    public let id = UUID()
    public var name: String
    public var value: String
    public var isProtected: Bool

    public init(name: String = "", value: String = "", isProtected: Bool = false) {
        self.name = name
        self.value = value
        self.isProtected = isProtected
    }

    public var description: String { "EditableField(<redacted>)" }
}

/// `data` is set only for a file picked in this editor and not yet in the vault.
public struct EditableAttachment: Identifiable, Equatable, Sendable, CustomStringConvertible {
    public let id = UUID()
    public let name: String
    public let size: Int64
    let data: Data?

    public var isPending: Bool { data != nil }
    public var description: String { "EditableAttachment(size: \(size))" }
}

public struct GroupChoice: Identifiable, Equatable, Sendable {
    public let id: GroupID
    /// "Web / Work", for the picker.
    public let path: String
}

public enum EditorSaveResult: Equatable, Sendable {
    case saved
    /// The change is in the open vault but not in the file.
    case notSaved(SaveFailure)
    case invalid(VaultEditError.Reason)
}

/// New or existing entry. The password and protected values are revealed once, when the editor
/// opens, and live in these strings until it closes.
@MainActor
@Observable
public final class EntryEditorModel {
    public enum Mode: Equatable, Sendable {
        case create(in: GroupID)
        case edit(EntryID)
    }

    public var title = ""
    public var username = ""
    public var password = ""
    public var url = ""
    public var notes = ""
    public var fields: [EditableField] = []
    public private(set) var attachments: [EditableAttachment] = []
    @ObservationIgnored private var removedAttachments: [String] = []
    public private(set) var tags: [String] = []
    public var tagInput = ""
    public var favorite = false
    /// Unix seconds; `nil` means the entry never expires.
    public var expiresAt: Int64?
    public private(set) var knownTags: [String] = []
    public var group: GroupID
    public private(set) var groups: [GroupChoice] = []
    public private(set) var unavailable = false

    public let mode: Mode
    @ObservationIgnored private let controller: VaultSessionController
    @ObservationIgnored private var originalGroup: GroupID
    @ObservationIgnored private let access: () -> KeepAccess

    public init(controller: VaultSessionController, mode: Mode, access: @escaping () -> KeepAccess = { .free }) {
        self.controller = controller
        self.access = access
        self.mode = mode
        let start: GroupID = if case let .create(group) = mode { group } else { GroupID("") }
        group = start
        originalGroup = start
    }

    public var isSaving: Bool { controller.isSaving }
    public var isNew: Bool { if case .create = mode { true } else { false } }

    public func load() {
        groups = Self.groupChoices(controller)
        knownTags = Array(Set(((try? controller.entries()) ?? []).flatMap(\.tags)))
            .sorted { $0.localizedStandardCompare($1) == .orderedAscending }
        guard case let .edit(id) = mode else { return }
        do {
            let detail = try controller.entry(id)
            title = detail.summary.displayTitle
            username = Self.plain(detail.summary.username)
            url = Self.plain(detail.summary.url)
            notes = Self.plain(detail.notes)
            tags = detail.summary.tags
            favorite = detail.summary.favorite
            expiresAt = detail.summary.expiresAt
            group = detail.summary.groupID
            originalGroup = detail.summary.groupID
            password = reveal(.password, id) ?? ""
            attachments = detail.attachments.map { EditableAttachment(name: $0.name, size: $0.size, data: nil) }
            fields = detail.customFields.map { field in
                switch field.value {
                case let .plain(value): EditableField(name: field.name, value: value)
                case .hidden: EditableField(name: field.name, value: reveal(.custom(field.name), id) ?? "", isProtected: true)
                }
            }
        } catch {
            unavailable = true
        }
    }

    public func generatePassword(_ options: PasswordOptions) -> Bool {
        guard let secret = try? PasswordGenerator.generate(options) else { return false }
        defer { secret.wipe() }
        guard let text = try? secret.withString({ $0 }) else { return false }
        password = text
        return true
    }

    public func addTag(_ text: String? = nil) {
        for tag in Self.splitTags(text ?? tagInput) where !tags.contains(tag) {
            tags.append(tag)
        }
        if !tagInput.isEmpty { tagInput = "" }
    }

    public func removeTag(_ tag: String) {
        tags.removeAll { $0 == tag }
    }

    public var tagSuggestions: [String] {
        let typed = tagInput.trimmingCharacters(in: .whitespaces)
        return Array(knownTags.filter { !tags.contains($0) && (typed.isEmpty || $0.localizedCaseInsensitiveContains(typed)) }.prefix(8))
    }

    static func splitTags(_ text: String) -> [String] {
        text.split(whereSeparator: { $0 == "," || $0 == ";" }).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    /// Returns false when the file is over the core's limit.
    @discardableResult
    public func addAttachment(name: String, data: Data) -> Bool {
        guard Int64(data.count) <= maxAttachmentBytes else { return false }
        attachments.append(EditableAttachment(name: name, size: Int64(data.count), data: data))
        return true
    }

    public func removeAttachment(_ attachment: EditableAttachment) {
        if !attachment.isPending { removedAttachments.append(attachment.name) }
        attachments.removeAll { $0.id == attachment.id }
    }

    public func addField() {
        fields.append(EditableField())
    }

    /// Turns a pasted otpauth link or secret key into the entry's `otp` field. Returns why it was refused, if it was.
    public func addTotp(_ input: String) -> TotpError.Reason? {
        let setup: TotpSetup
        do {
            setup = try RustKeepCore().normalizeTotp(Data(input.utf8), access: access())
        } catch {
            return error.reason
        }
        defer { setup.uri.wipe() }
        guard let uri = try? setup.uri.withString({ $0 }) else { return .malformed }
        if let index = fields.firstIndex(where: { $0.name.trimmingCharacters(in: .whitespaces) == otpFieldName }) {
            fields[index].value = uri
            fields[index].isProtected = true
        } else {
            fields.append(EditableField(name: otpFieldName, value: uri, isProtected: true))
        }
        return nil
    }

    /// The entry the last successful save wrote.
    public private(set) var savedEntry: EntryID?

    public func save() async -> EditorSaveResult {
        var draft = EntryDraft(
            title: title,
            username: username,
            password: Data(password.utf8),
            url: url.trimmingCharacters(in: .whitespacesAndNewlines),
            notes: notes,
            customFields: fields
                .filter { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty || !$0.value.isEmpty }
                .map { CustomFieldDraft(name: $0.name.trimmingCharacters(in: .whitespaces), value: Data($0.value.utf8), isProtected: $0.isProtected) },
            tags: tags + Self.splitTags(tagInput).filter { !tags.contains($0) },
            favorite: favorite,
            expiresAt: expiresAt
        )
        defer { draft.wipe() }
        let target = group
        let moved = target != originalGroup
        let removals = removedAttachments
        let additions = attachments.compactMap { item in item.data.map { (item.name, $0) } }
        let plus = access()
        let result: EditResult<EntryID?>
        switch mode {
        case let .create(initial):
            let destination = target.rawValue.isEmpty ? initial : target
            let snapshot = draft
            result = await controller.edit { session throws(VaultEditError) in
                let id = try session.createEntry(in: destination, snapshot)
                for (name, data) in additions { _ = try session.addAttachment(name, data: data, to: id, access: plus) }
                return id
            }
        case let .edit(id):
            let snapshot = draft
            result = await controller.edit { session throws(VaultEditError) in
                try session.updateEntry(id, snapshot)
                if moved { try session.moveEntry(id, to: target) }
                for name in removals { try session.removeAttachment(name, from: id) }
                for (name, data) in additions { _ = try session.addAttachment(name, data: data, to: id, access: plus) }
                return nil
            }
        }
        switch result {
        case let .saved(created):
            originalGroup = target
            if let created { savedEntry = created } else if case let .edit(id) = mode { savedEntry = id }
            return .saved
        case let .notSaved(_, failure):
            originalGroup = target
            if case let .edit(id) = mode { reloadAttachments(id) }
            return .notSaved(failure)
        case let .failed(error):
            return .invalid(error.reason)
        }
    }

    private func reloadAttachments(_ id: EntryID) {
        removedAttachments = []
        attachments = ((try? controller.entry(id).attachments) ?? []).map { EditableAttachment(name: $0.name, size: $0.size, data: nil) }
    }

    /// Drops the revealed values when the editor closes.
    public func discard() {
        attachments = attachments.filter { !$0.isPending }
        removedAttachments = []
        password = ""
        fields = fields.map { EditableField(name: $0.name, value: "", isProtected: $0.isProtected) }
    }

    private func reveal(_ field: SecretField, _ id: EntryID) -> String? {
        guard let secret = try? controller.reveal(field, for: id) else { return nil }
        defer { secret.wipe() }
        return try? secret.withString { $0 }
    }

    private static func plain(_ text: EntryText) -> String {
        if case let .plain(value) = text { return value }
        return ""
    }

    /// Every group as a path, root first, recycle bin last.
    static func groupChoices(_ controller: VaultSessionController) -> [GroupChoice] {
        guard case let .unlocked(info) = controller.state, let all = try? controller.groups() else { return [] }
        let byID = Dictionary(uniqueKeysWithValues: all.map { ($0.id, $0) })
        func path(_ group: VaultGroup) -> String {
            var names = [group.name]
            var parent = group.parentID
            while let id = parent, id != info.rootGroupID, let next = byID[id] {
                names.insert(next.name, at: 0)
                parent = next.parentID
            }
            return group.id == info.rootGroupID ? info.name : names.joined(separator: " / ")
        }
        return all
            .map { GroupChoice(id: $0.id, path: path($0)) }
            .sorted { lhs, rhs in
                if lhs.id == info.rootGroupID { return true }
                if rhs.id == info.rootGroupID { return false }
                return lhs.path.localizedStandardCompare(rhs.path) == .orderedAscending
            }
    }
}

/// Group and entry operations from the browser, each written to the file at once.
@MainActor
public struct VaultActions {
    private let controller: VaultSessionController

    public init(controller: VaultSessionController) {
        self.controller = controller
    }

    public var recycleBin: GroupID? {
        guard case let .unlocked(info) = controller.state else { return nil }
        return info.recycleBinID
    }

    public func isInRecycleBin(_ group: GroupID) -> Bool {
        guard let bin = recycleBin, let all = try? controller.groups() else { return false }
        var current: GroupID? = group
        while let id = current {
            if id == bin { return true }
            current = all.first { $0.id == id }?.parentID
        }
        return false
    }

    public func isEmpty(_ group: GroupID) -> Bool {
        let groups = (try? controller.groups()) ?? []
        let entries = (try? controller.entries()) ?? []
        return !groups.contains { $0.parentID == group } && !entries.contains { $0.groupID == group }
    }

    public func createGroup(in parent: GroupID, name: String) async -> EditResult<GroupID> {
        await controller.edit { session throws(VaultEditError) in try session.createGroup(in: parent, name: name) }
    }

    public func renameGroup(_ id: GroupID, to name: String) async -> EditResult<Void> {
        await controller.edit { session throws(VaultEditError) in try session.renameGroup(id, name: name) }
    }

    public func deleteGroup(_ id: GroupID, permanent: Bool) async -> EditResult<DeleteOutcome> {
        await controller.edit { session throws(VaultEditError) in try session.deleteGroup(id, recursive: true, permanent: permanent) }
    }

    public func setGroupIcon(_ id: GroupID, _ icon: Int) async -> EditResult<Void> {
        await controller.edit { session throws(VaultEditError) in try session.setGroupIcon(id, icon) }
    }

    /// Gives every entry that still has the default icon one by its address. Nothing is fetched.
    public func assignIconsByURL() async -> EditResult<Int> {
        await controller.edit { session throws(VaultEditError) in try session.assignIconsByURL() }
    }

    /// The entry stays; the version with the passkey goes to its history.
    public func removePasskey(_ id: EntryID) async -> EditResult<Void> {
        await controller.edit { session throws(VaultEditError) in try session.removePasskey(id) }
    }

    public func setEntryIcon(_ id: EntryID, _ icon: Int) async -> EditResult<Void> {
        await controller.edit { session throws(VaultEditError) in try session.setEntryIcon(id, icon) }
    }

    public func setFavorite(_ id: EntryID, _ favorite: Bool) async -> EditResult<Void> {
        await controller.edit { session throws(VaultEditError) in try session.setFavorite(id, favorite) }
    }

    public func deleteEntry(_ id: EntryID, permanent: Bool) async -> EditResult<DeleteOutcome> {
        await controller.edit { session throws(VaultEditError) in try session.deleteEntry(id, permanent: permanent) }
    }
}
