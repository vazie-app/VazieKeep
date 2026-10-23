import Foundation
import KeepCore
import Observation

public enum AutoFillRequest: Equatable, Sendable {
    /// The user picked Vazie Keep in the password list: show matching entries and search.
    case list([ServiceOrigin])
    /// The user picked one of our QuickType suggestions.
    case credential(recordIdentifier: String, service: ServiceOrigin?)
    /// A site that takes a passkey: show its passkeys here next to the matching logins.
    case passkeyList([ServiceOrigin], PasskeyQuery)
    /// The user picked one of our passkey suggestions.
    case passkey(recordIdentifier: String, PasskeyQuery)
    /// The user chose Vazie Keep to keep a new passkey.
    case passkeyRegistration(PasskeyRegistrationQuery)
}

/// What iOS passes on from a site that asks to sign in with a passkey. iOS has already checked that the
/// page or the app belongs to `rpID`.
public struct PasskeyQuery: Equatable, Sendable {
    public var rpID: String
    public var clientDataHash: Data
    /// Empty means any passkey of the site.
    public var allowedCredentials: [Data]

    public init(rpID: String, clientDataHash: Data, allowedCredentials: [Data] = []) {
        self.rpID = rpID
        self.clientDataHash = clientDataHash
        self.allowedCredentials = allowedCredentials
    }
}

public struct PasskeyRegistrationQuery: Equatable, Sendable {
    public var rpID: String
    public var userName: String
    public var userHandle: Data
    public var clientDataHash: Data
    public var acceptsES256: Bool

    public init(rpID: String, userName: String, userHandle: Data, clientDataHash: Data, acceptsES256: Bool = true) {
        self.rpID = rpID
        self.userName = userName
        self.userHandle = userHandle
        self.clientDataHash = clientDataHash
        self.acceptsES256 = acceptsES256
    }
}

public enum AutoFillProblem: Equatable, Sendable {
    case notConfigured
    /// The suggestion no longer points at an entry of this vault for this site.
    case suggestionOutdated
    /// The new passkey could not be written into the vault file, so the site was not given one.
    case passkeyNotSaved
    /// The site asks for a kind of passkey Vazie Keep does not make.
    case passkeyUnsupported
}

public enum AutoFillPhase: Equatable, Sendable {
    case locked
    case choosing
    case done
    case failed(AutoFillProblem)
}

/// What the extension hands back to iOS. The password reaches it once, for the chosen entry.
@MainActor
public protocol AutoFillCompletion: AnyObject {
    func complete(user: String, password: String)
    func complete(assertion: PasskeyAssertion, query: PasskeyQuery)
    func complete(registration: CreatedPasskey, query: PasskeyRegistrationQuery)
    func cancel()
}

/// The AutoFill extension's flow, in the extension's own process: unlock (Face ID or the master
/// password; the key file is read every time), list entries from summaries, reveal the password
/// of the one entry the user picks, complete, close the session. Nothing is kept between requests.
@MainActor
@Observable
public final class AutoFillFlow {
    public private(set) var phase: AutoFillPhase = .locked
    public private(set) var suggested: [EntrySummary] = []
    public private(set) var others: [EntrySummary] = []
    /// The site's passkeys in this vault, when the request can take one.
    public private(set) var passkeys: [EntrySummary] = []
    public private(set) var searchResults: [EntrySummary]?
    public private(set) var notice: AutoFillProblem?
    public var query = ""

    @ObservationIgnored public let controller: VaultSessionController
    @ObservationIgnored public let biometric: BiometricUnlock
    @ObservationIgnored private let request: AutoFillRequest
    @ObservationIgnored private weak var completion: (any AutoFillCompletion)?
    @ObservationIgnored private let log: (String) -> Void

    public init(request: AutoFillRequest, controller: VaultSessionController, biometric: BiometricUnlock, completion: any AutoFillCompletion, log: @escaping (String) -> Void = { _ in }) {
        self.request = request
        self.controller = controller
        self.biometric = biometric
        self.completion = completion
        self.log = log
        // Face ID unlock is turned on only in the app; the extension never keeps the material.
        controller.shouldRetainUnlockMaterial = { false }
        if controller.reference == nil {
            phase = .failed(.notConfigured)
        }
    }

    public var requestedServices: [ServiceOrigin] {
        switch request {
        case let .list(services), let .passkeyList(services, _): services
        case let .credential(_, service): service.map { [$0] } ?? []
        case .passkey, .passkeyRegistration: []
        }
    }

    /// Call after `controller` reports `.unlocked`.
    public func vaultUnlocked() {
        guard case let .unlocked(info) = controller.state, let entries = try? controller.entries() else { return }
        let candidates = entries.filter(\.hasPassword)
        let services = requestedServices
        let ranked = candidates.compactMap { entry in AutoFillMatcher.match(entry, requests: services).map { (entry, $0) } }
        suggested = ranked.sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.0.displayTitle.localizedStandardCompare($1.0.displayTitle) == .orderedAscending }.map(\.0)
        let suggestedIDs = Set(suggested.map(\.id))
        others = candidates.filter { !suggestedIDs.contains($0.id) }
            .sorted { $0.displayTitle.localizedStandardCompare($1.displayTitle) == .orderedAscending }
        log("autofill: \(suggested.count) suggested of \(candidates.count)")

        switch request {
        case let .passkeyList(_, query):
            passkeys = held(for: query)
        case let .passkey(record, query):
            if let id = AutoFillIndex.parse(recordIdentifier: record, fingerprint: info.fingerprint), held(for: query).contains(where: { $0.id == id }) {
                usePasskey(id)
                return
            }
            passkeys = held(for: query)
            notice = .suggestionOutdated
        case let .passkeyRegistration(query):
            register(query, in: info.rootGroupID)
            return
        case .list, .credential:
            break
        }

        if case let .credential(record, service) = request {
            if let id = AutoFillIndex.parse(recordIdentifier: record, fingerprint: info.fingerprint),
               let entry = candidates.first(where: { $0.id == id }),
               service.map({ AutoFillMatcher.match(entry, requests: [$0]) != nil }) ?? true {
                choose(entry.id)
                return
            }
            notice = .suggestionOutdated
        }
        phase = .choosing
    }

    /// Uses the core's search; secrets are never searched.
    public func search() async {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            searchResults = nil
            return
        }
        let results = (try? await controller.search(text))?.filter(\.hasPassword)
        if query.trimmingCharacters(in: .whitespacesAndNewlines) == text { searchResults = results ?? [] }
    }

    /// Reveals the password of this entry only, hands it to iOS, then closes the session.
    public func choose(_ id: EntryID) {
        guard controller.isUnlocked, phase != .done else { return }
        defer { controller.lock() }
        guard let detail = try? controller.entry(id), let secret = try? controller.reveal(.password, for: id) else {
            notice = .suggestionOutdated
            clearLists()
            phase = .locked
            return
        }
        defer { secret.wipe() }
        let user = if case let .plain(name) = detail.summary.username { name } else { "" }
        guard (try? secret.withString({ completion?.complete(user: user, password: $0) })) != nil else { return }
        phase = .done
        log("autofill: filled")
    }

    private func held(for query: PasskeyQuery) -> [EntrySummary] {
        controller.passkeys(rpID: query.rpID).filter { entry in
            query.allowedCredentials.isEmpty || entry.passkey?.credentialIDData.map(query.allowedCredentials.contains) == true
        }
    }

    /// Signs the site's request with this entry's passkey, hands the answer to iOS, then closes the session.
    public func usePasskey(_ id: EntryID) {
        guard controller.isUnlocked, phase != .done else { return }
        let query: PasskeyQuery
        switch request {
        case let .passkeyList(_, asked), let .passkey(_, asked): query = asked
        default: return
        }
        defer { controller.lock() }
        guard held(for: query).contains(where: { $0.id == id }),
              let assertion = try? controller.signPasskey(id, rpID: query.rpID, clientDataHash: query.clientDataHash) else {
            notice = .suggestionOutdated
            clearLists()
            phase = .locked
            return
        }
        completion?.complete(assertion: assertion, query: query)
        phase = .done
        log("autofill: passkey used")
    }

    /// The person already chose to keep the passkey here, so it is made as soon as the vault opens. The site
    /// gets it only after the vault file holds it.
    private func register(_ query: PasskeyRegistrationQuery, in group: GroupID) {
        guard query.acceptsES256 else {
            controller.lock()
            phase = .failed(.passkeyUnsupported)
            return
        }
        phase = .choosing
        Task {
            let result = await controller.edit { session throws(VaultEditError) in
                try session.createPasskey(in: group, rpID: query.rpID, rpName: query.rpID, userName: query.userName, userHandle: query.userHandle)
            }
            controller.lock()
            if case let .saved(made) = result {
                completion?.complete(registration: made, query: query)
                phase = .done
                log("autofill: passkey made")
            } else {
                phase = .failed(.passkeyNotSaved)
            }
        }
    }

    public var isRegistering: Bool {
        if case .passkeyRegistration = request { return true }
        return false
    }

    public func cancel() {
        controller.lock()
        phase = .done
        completion?.cancel()
    }

    /// The host app went to the background or memory ran low: nothing stays open.
    public func suspend() {
        guard controller.isUnlocked else { return }
        controller.lock()
        phase = .locked
        clearLists()
    }

    private func clearLists() {
        suggested = []
        others = []
        passkeys = []
        searchResults = nil
    }
}
