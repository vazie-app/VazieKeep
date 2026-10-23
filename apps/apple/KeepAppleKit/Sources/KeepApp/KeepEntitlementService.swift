import Foundation
import KeepCore
import Observation

public struct AccountInfo: Equatable, Sendable {
    public let accountID: String
    public let email: String?
}

/// Why Keep+ could not be checked just now. It is about the account, never about a vault.
public enum EntitlementProblem: Equatable, Sendable {
    /// The backend could not be reached. A valid lease keeps working.
    case offline
    /// The session expired or was revoked: sign in again to keep Keep+ up to date.
    case sessionExpired
    /// The device date and time disagree with the backend's.
    case clockOff
    /// The backend answered with something that is not a valid lease for this account.
    case failed
}

public enum CodeRequestResult: Equatable, Sendable { case sent, invalidEmail, rateLimited, unavailable }
public enum SignInResult: Equatable, Sendable { case success, invalidCode, rateLimited, unavailable }

/// Keep+ for the app: the account on this device, the latest signed lease, and what that allows now.
///
/// Free needs none of it. Nothing here is awaited by opening a vault, nothing here is sent a vault
/// identifier, and a failure here only ever changes what the account screen says.
@MainActor
@Observable
public final class KeepEntitlementService {
    public static let refreshAfter: Int64 = 5 * 60
    public static let expiringSoon: Int64 = 24 * 60 * 60
    private static let persistAfter: Int64 = 60

    public private(set) var account: AccountInfo?
    public private(set) var lease: KeepLease?
    public private(set) var access: KeepAccess = .free
    public private(set) var refreshing = false
    public private(set) var problem: EntitlementProblem?

    private let api: any VazieAccountApi
    private let store: any AccountStore
    private let core: any KeepEntitlementCore
    private let keys: [LeaseKey]
    private let now: @Sendable () -> Int64
    private var stored: StoredAccount?

    public var sessionToken: String? { stored?.sessionToken }

    public init(
        api: any VazieAccountApi,
        store: any AccountStore,
        core: any KeepEntitlementCore,
        keys: [LeaseKey],
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970) }
    ) {
        self.api = api
        self.store = store
        self.core = core
        self.keys = keys
        self.now = now
    }

    /// "kid:publicKey,kid2:publicKey2", as written into the app's Info.plist.
    public static func parseKeys(_ text: String) -> [LeaseKey] {
        text.split(separator: ",").compactMap { entry in
            let parts = entry.trimmingCharacters(in: .whitespaces).split(separator: ":", omittingEmptySubsequences: false)
            guard parts.count == 2, !parts[0].isEmpty, !parts[1].isEmpty else { return nil }
            return LeaseKey(kid: String(parts[0]), publicKey: String(parts[1]))
        }
    }

    /// Reads what is stored and judges it with the current clock. Does no network.
    public func start() {
        stored = store.load()
        publish()
    }

    /// Call when the app comes to the foreground: the clock may have moved.
    public func reevaluate() {
        if stored != nil { publish() }
    }

    public func requestCode(email: String) async -> CodeRequestResult {
        do {
            try await api.startEmailSignIn(email: email.trimmingCharacters(in: .whitespaces))
            return .sent
        } catch {
            return switch error {
            case .invalidEmail: .invalidEmail
            case .rateLimited: .rateLimited
            default: .unavailable
            }
        }
    }

    public func signIn(email: String, code: String) async -> SignInResult {
        do {
            await begin(try await api.verifyEmailCode(email: email.trimmingCharacters(in: .whitespaces), code: code.trimmingCharacters(in: .whitespaces)))
            return .success
        } catch {
            return switch error {
            case .invalidCode: .invalidCode
            case .rateLimited: .rateLimited
            default: .unavailable
            }
        }
    }

    /// For a development backend only; production answers `notEnabled`.
    public func devSignIn() async -> SignInResult {
        do {
            await begin(try await api.devSignIn())
            return .success
        } catch {
            return .unavailable
        }
    }

    private func begin(_ session: ApiSession) async {
        // Another account: the old lease is dropped, never carried over.
        let same = stored?.accountID == session.accountID
        let account = StoredAccount(
            accountID: session.accountID,
            email: session.email,
            sessionToken: session.token,
            leaseToken: same ? stored?.leaseToken : nil,
            lastObservedDeviceTime: same ? stored?.lastObservedDeviceTime ?? 0 : 0,
            lastRefreshAt: 0
        )
        store.save(account)
        stored = account
        publish()
        await fetch(account)
    }

    /// Asks the backend for a lease when one is due (or `force`). Safe to call often.
    public func refresh(force: Bool = false) async {
        guard let account = stored, !refreshing else { return }
        if !force && !due(account) { return }
        await fetch(account)
    }

    private func due(_ account: StoredAccount) -> Bool {
        let current = now()
        return account.leaseToken == nil
            || access.needsOnlineVerification
            || current - account.lastRefreshAt >= Self.refreshAfter
            || (access.leaseValidUntil.map { $0 - current < Self.expiringSoon } ?? false)
    }

    private func fetch(_ account: StoredAccount) async {
        refreshing = true
        problem = nil
        defer { refreshing = false }
        do {
            let token = try await api.fetchLease(token: account.sessionToken)
            let verified = try core.verifyLease(token, keys: keys)
            guard verified.subject == account.accountID else { throw LeaseVerifyFailure.inconsistent }
            let updated = StoredAccount(
                accountID: account.accountID, email: account.email, sessionToken: account.sessionToken, leaseToken: token,
                // The backend's clock is the reference after a successful check.
                lastObservedDeviceTime: verified.issuedAt, lastRefreshAt: now()
            )
            store.save(updated)
            stored = updated
            publish()
            if access.needsOnlineVerification && verified.tier == .keepPlus { problem = .clockOff }
        } catch let error as AccountApiError {
            problem = error == .sessionRejected ? .sessionExpired : .offline
        } catch {
            problem = .failed
        }
    }

    /// Removes the account, its session and the cached lease from this device. Vaults are untouched.
    public func signOut() async {
        let gone = stored
        forgetLocally()
        // After the local state is gone, so a slow or failing network never keeps Keep+ switched on.
        if let gone { try? await api.signOut(token: gone.sessionToken) }
    }

    public enum DeleteResult: Equatable, Sendable { case deleted, failed }

    /// Deletes the account on the backend and then forgets it here. Nothing is forgotten unless the backend did it, so
    /// a failed deletion leaves the person signed in and able to try again. A session the backend no longer knows has
    /// no account left to delete.
    public func deleteAccount() async -> DeleteResult {
        guard let current = stored else { return .deleted }
        do {
            try await api.deleteAccount(token: current.sessionToken)
        } catch .sessionRejected {
        } catch {
            return .failed
        }
        forgetLocally()
        return .deleted
    }

    private func forgetLocally() {
        stored = nil
        store.clear()
        account = nil
        lease = nil
        access = .free
        problem = nil
    }

    /// The one place the app asks "may this person use that?". Free features are never asked about.
    public func canUse(_ feature: PlusFeature) -> Bool {
        core.canUse(feature, access: access)
    }

    private func publish() {
        guard let current = stored else {
            account = nil; lease = nil; access = .free
            return
        }
        let verified = current.leaseToken.flatMap { try? core.verifyLease($0, keys: keys) }.flatMap { $0.subject == current.accountID ? $0 : nil }
        let evaluated = core.evaluateAccess(lease: verified, now: now(), lastObserved: current.lastObservedDeviceTime)
        if evaluated.observedDeviceTime - current.lastObservedDeviceTime >= Self.persistAfter {
            var next = current
            next.lastObservedDeviceTime = evaluated.observedDeviceTime
            store.save(next)
            stored = next
        }
        account = AccountInfo(accountID: current.accountID, email: current.email)
        lease = verified
        access = evaluated
    }
}
