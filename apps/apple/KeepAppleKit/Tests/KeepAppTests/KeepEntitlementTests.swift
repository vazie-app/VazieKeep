import CryptoKit
import Foundation
import KeepApp
import KeepCore
import Synchronization
import Testing

/// The Keep+ vectors shared with the Rust core, the backend and Android, verified through the real core.
@Suite("Keep+ lease vectors")
struct LeaseVectorTests {
    struct Case { let name: String; let token: String; let valid: Bool }

    static func load() throws -> (keys: [LeaseKey], cases: [Case]) {
        let data = try Data(contentsOf: vectorURL("keep-lease/vectors.json"))
        let root = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let keys = (root["keys"] as? [[String: String]] ?? []).map { LeaseKey(kid: $0["kid"] ?? "", publicKey: $0["public_key"] ?? "") }
        let cases = (root["cases"] as? [[String: Any]] ?? []).map {
            Case(name: $0["name"] as? String ?? "", token: $0["token"] as? String ?? "", valid: $0["valid"] as? Bool ?? false)
        }
        return (keys, cases)
    }

    @Test func everyVectorIsAcceptedOrRejectedAsDeclared() throws {
        let (keys, cases) = try Self.load()
        #expect(cases.count >= 15 && keys.count == 1)
        for item in cases {
            let result = Result { () throws(LeaseVerifyFailure) -> KeepLease in try RustKeepCore().verifyLease(item.token, keys: keys) }
            let accepted = (try? result.get()) != nil
            #expect(item.valid == accepted, "\(item.name)")
        }
    }

    @Test func tamperingAndUntrustedKeysAreDistinguished() throws {
        let (keys, cases) = try Self.load()
        func token(_ name: String) -> String { cases.first { $0.name == name }?.token ?? "" }
        #expect(throws: LeaseVerifyFailure.badSignature) { try RustKeepCore().verifyLease(token("tier_tampered"), keys: keys) }
        #expect(throws: LeaseVerifyFailure.unknownKey) { try RustKeepCore().verifyLease(token("plus_90_days"), keys: []) }
        #expect(throws: LeaseVerifyFailure.unsupportedAlgorithm) { try RustKeepCore().verifyLease(token("alg_none"), keys: keys) }
    }

    @Test func aVerifiedLeaseBecomesAccessThroughTheCoreClockRules() throws {
        let (keys, cases) = try Self.load()
        let core = RustKeepCore()
        let lease = try core.verifyLease(cases.first { $0.name == "plus_90_days" }?.token ?? "", keys: keys)
        let fresh = core.evaluateAccess(lease: lease, now: lease.issuedAt + 60, lastObserved: lease.issuedAt)
        #expect(fresh.state == .plusActive && core.canUse(.multiVault, access: fresh))
        let rolledBack = core.evaluateAccess(lease: lease, now: lease.issuedAt - 10 * 86_400, lastObserved: lease.issuedAt + 86_400)
        #expect(!rolledBack.plusFeatures && rolledBack.needsOnlineVerification)
        #expect(core.evaluateAccess(lease: lease, now: lease.leaseExpiresAt, lastObserved: lease.leaseExpiresAt - 1).state == .plusExpired)
        #expect(core.evaluateAccess(lease: nil, now: 1, lastObserved: 0).state == .free)
    }
}

private let day: Int64 = 86_400
private let seed = Data((0..<32).map { index in
    let hex = Array("5645525349454b454550544553544f4e4c59ffffffffffffffffffffffffffff".prefix(64))
    return UInt8(String(hex[index * 2 ... index * 2 + 1]), radix: 16) ?? 0
})
private let signingKey = try! Curve25519.Signing.PrivateKey(rawRepresentation: seed)
private let trustedKey = LeaseKey(kid: "test-1", publicKey: "fqGTImPbO7V1Wg9BLZGK6cVsnIpw5cNdSwvlILlZA-s")

private func b64(_ data: Data) -> String {
    data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
}

private func signLease(_ payload: String, kid: String = "test-1") -> String {
    let input = b64(Data(#"{"alg":"EdDSA","kid":"\#(kid)","typ":"vazie-keep-lease+jws"}"#.utf8)) + "." + b64(Data(payload.utf8))
    return input + "." + b64((try? signingKey.signature(for: Data(input.utf8))) ?? Data())
}

private final class TestClock: Sendable {
    let value = Mutex<Int64>(1_800_000_000)
}

private final class MemoryStore: AccountStore, @unchecked Sendable {
    private let lock = Mutex<StoredAccount?>(nil)
    private let clears = Mutex<Int>(0)
    var account: StoredAccount? { get { lock.withLock { $0 } } set { lock.withLock { $0 = newValue } } }
    var clearCount: Int { clears.withLock { $0 } }
    func load() -> StoredAccount? { account }
    func save(_ account: StoredAccount) { self.account = account }
    func clear() { account = nil; clears.withLock { $0 += 1 } }
}

private final class FakeApi: VazieAccountApi, @unchecked Sendable {
    fileprivate struct State {
        var accountID = "acct-1"
        var tier = "keep_plus"
        var lifetime = false
        var entitlementEnd: Int64?
        var failure: AccountApiError?
        var fetches = 0
        var signOuts = 0
        var deletions = 0
        var codeValid = true
        var issuedAtOverride: Int64?
        var now: Int64 = 1_800_000_000
    }
    private let state = Mutex(State())
    func update(_ change: (inout State) -> Void) {
        state.withLock { current in
            var copy = current
            change(&copy)
            current = copy
        }
    }
    var fetches: Int { state.withLock { $0.fetches } }
    var signOuts: Int { state.withLock { $0.signOuts } }
    var deletions: Int { state.withLock { $0.deletions } }

    func startEmailSignIn(email: String) async throws(AccountApiError) { if let f = state.withLock({ $0.failure }) { throw f } }
    func verifyEmailCode(email: String, code: String) async throws(AccountApiError) -> ApiSession {
        let (valid, id) = state.withLock { ($0.codeValid, $0.accountID) }
        if !valid { throw .invalidCode }
        return ApiSession(accountID: id, email: email, token: "session-\(id)")
    }
    func devSignIn() async throws(AccountApiError) -> ApiSession {
        let id = state.withLock { $0.accountID }
        return ApiSession(accountID: id, email: nil, token: "session-\(id)")
    }
    func fetchLease(token: String) async throws(AccountApiError) -> String {
        let (failure, _) = state.withLock { s -> (AccountApiError?, Void) in s.fetches += 1; return (s.failure, ()) }
        if let failure { throw failure }
        return lease()
    }
    func signOut(token: String) async throws(AccountApiError) { state.withLock { $0.signOuts += 1 } }
    func deleteAccount(token: String) async throws(AccountApiError) {
        if let f = state.withLock({ $0.failure }) { throw f }
        state.withLock { $0.deletions += 1 }
    }

    func lease(subject: String? = nil) -> String {
        let s = state.withLock { $0 }
        let issued = s.issuedAtOverride ?? s.now
        let leaseEnd = issued + 30 * day
        let expires = s.entitlementEnd.map { min(leaseEnd, $0) } ?? leaseEnd
        let end = s.entitlementEnd.map(String.init) ?? "null"
        return signLease(#"{"version":1,"issuer":"vazie","subject":"\#(subject ?? s.accountID)","product":"keep","tier":"\#(s.tier)","lifetime":\#(s.lifetime),"issued_at":\#(issued),"lease_expires_at":\#(expires),"entitlement_expires_at":\#(end),"features_version":1,"revision":2,"nonce":"n\#(issued)"}"#)
    }
}

@MainActor
@Suite("Keep+ entitlement service")
struct KeepEntitlementServiceTests {
    fileprivate let clock = TestClock()
    fileprivate let store = MemoryStore()
    fileprivate let api = FakeApi()

    func service() -> KeepEntitlementService {
        let clock = clock
        return KeepEntitlementService(api: api, store: store, core: RustKeepCore(), keys: [trustedKey]) { clock.value.withLock { $0 } }
    }

    func setNow(_ value: Int64) { clock.value.withLock { $0 = value }; api.update { $0.now = value } }
    var now: Int64 { clock.value.withLock { $0 } }

    @Test func withoutAnAccountTheAppIsFreeAndNothingIsAskedOfTheNetwork() async {
        let service = service()
        service.start()
        await service.refresh()
        #expect(service.access.state == .free && service.account == nil && api.fetches == 0)
    }

    @Test func signingInFetchesALeaseAndSwitchesKeepPlusOn() async {
        api.update { $0.entitlementEnd = now + 90 * day }
        let service = service()
        #expect(await service.signIn(email: "a@b.c", code: "123456") == .success)
        #expect(service.access.state == .plusActive && service.access.plusFeatures && service.account?.email == "a@b.c")
        #expect(store.account?.leaseToken != nil)
        #expect(service.canUse(.multiVault))
    }

    @Test func theCachedLeaseWorksOfflineAfterARestartAndEndsAtItsExpiry() async {
        api.update { $0.entitlementEnd = now + 90 * day }
        _ = await service().signIn(email: "a@b.c", code: "1")
        api.update { $0.failure = .unreachable }
        setNow(now + 10 * day)
        let restarted = service()
        restarted.start()
        #expect(restarted.access.state == .plusOfflineGrace)
        await restarted.refresh(force: true)
        #expect(restarted.problem == .offline && restarted.access.plusFeatures)
        setNow(now + 21 * day)
        restarted.reevaluate()
        #expect(!restarted.access.plusFeatures && restarted.access.state == .plusExpired)
    }

    @Test func settingTheClockBackDoesNotExtendALease() async {
        api.update { $0.entitlementEnd = now + 90 * day }
        let service = service()
        _ = await service.signIn(email: "a@b.c", code: "1")
        setNow(now + 20 * day)
        service.reevaluate()
        #expect(service.access.plusFeatures)
        setNow(now - 25 * day)
        service.reevaluate()
        #expect(!service.access.plusFeatures && service.access.needsOnlineVerification)
        #expect(!service.canUse(.totp))
    }

    @Test func aLeaseShorterThanThirtyDaysIsNotExtended() async {
        api.update { $0.entitlementEnd = now + 5 * day }
        let service = service()
        _ = await service.signIn(email: "a@b.c", code: "1")
        #expect(service.access.leaseValidUntil == now + 5 * day)
    }

    @Test func aLifetimeShowsAsLifetimeAndAFreeAccountIsNotAProblem() async {
        api.update { $0.lifetime = true }
        let service = service()
        _ = await service.signIn(email: "a@b.c", code: "1")
        #expect(service.access.state == .plusLifetime)
        api.update { $0.lifetime = false; $0.tier = "free" }
        await service.refresh(force: true)
        #expect(service.access.state == .free && service.problem == nil && service.account != nil)
    }

    @Test func deletingTheAccountAsksTheBackendAndThenForgetsEverythingHere() async {
        api.update { $0.entitlementEnd = now + 90 * day }
        let service = service()
        _ = await service.signIn(email: "a@b.c", code: "1")
        #expect(await service.deleteAccount() == .deleted)
        #expect(api.deletions == 1 && store.account == nil && service.account == nil && service.access.state == .free)
    }

    @Test func aFailedDeletionForgetsNothingSoThePersonCanTryAgain() async {
        let service = service()
        _ = await service.signIn(email: "a@b.c", code: "1")
        api.update { $0.failure = .unreachable }
        #expect(await service.deleteAccount() == .failed)
        #expect(store.account != nil && service.account != nil)
        api.update { $0.failure = nil }
        #expect(await service.deleteAccount() == .deleted)
    }

    @Test func aSessionTheBackendNoLongerKnowsHasNoAccountLeftToDelete() async {
        let service = service()
        _ = await service.signIn(email: "a@b.c", code: "1")
        api.update { $0.failure = .sessionRejected }
        #expect(await service.deleteAccount() == .deleted)
        #expect(store.account == nil)
    }

    @Test func signingOutRemovesTheAccountAndTheLeaseAtOnce() async {
        api.update { $0.entitlementEnd = now + 90 * day }
        let service = service()
        _ = await service.signIn(email: "a@b.c", code: "1")
        await service.signOut()
        #expect(store.account == nil && store.clearCount == 1 && service.access.state == .free && service.account == nil && api.signOuts == 1)
    }

    @Test func anotherAccountNeverInheritsTheFirstOnesKeepPlus() async {
        api.update { $0.entitlementEnd = now + 90 * day }
        let service = service()
        _ = await service.signIn(email: "a@b.c", code: "1")
        #expect(service.access.plusFeatures)
        api.update { $0.accountID = "acct-2"; $0.tier = "free"; $0.entitlementEnd = nil; $0.failure = .unreachable }
        _ = await service.signIn(email: "x@y.z", code: "1")
        #expect(service.account?.accountID == "acct-2" && service.lease == nil && !service.access.plusFeatures)
    }

    @Test func aForgedLeaseALeaseForAnotherAccountAndAnUnknownKeyAreAllRejected() async {
        api.update { $0.entitlementEnd = now + 90 * day }
        let first = service()
        _ = await first.signIn(email: "a@b.c", code: "1")
        let good = store.account?.leaseToken ?? ""
        func replaceLease(_ token: String) {
            guard var current = store.account else { return }
            current.leaseToken = token
            store.account = current
        }
        let parts = good.split(separator: ".").map(String.init)
        let body = String(decoding: Data(base64Encoded: parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/") + "==") ?? Data(), as: UTF8.self)
        replaceLease(parts[0] + "." + b64(Data(body.replacingOccurrences(of: "keep_plus", with: "keep_plux").utf8)) + "." + parts[2])
        let a = service(); a.start(); #expect(!a.access.plusFeatures)
        replaceLease(api.lease(subject: "someone-else"))
        let b = service(); b.start(); #expect(!b.access.plusFeatures)
        replaceLease(signLease(#"{"version":1,"issuer":"vazie","subject":"acct-1","product":"keep","tier":"keep_plus","lifetime":true,"issued_at":\#(now),"lease_expires_at":\#(now + day),"entitlement_expires_at":null,"features_version":1,"revision":1,"nonce":"x"}"#, kid: "nobody"))
        let c = service(); c.start(); #expect(!c.access.plusFeatures)
    }

    @Test func anExpiredSessionKeepsTheLeaseAndIsReported() async {
        api.update { $0.entitlementEnd = now + 90 * day }
        let service = service()
        _ = await service.signIn(email: "a@b.c", code: "1")
        api.update { $0.failure = .sessionRejected }
        await service.refresh(force: true)
        #expect(service.problem == .sessionExpired && service.access.plusFeatures)
    }

    @Test func refreshHappensAtMostEveryFiveMinutesUnlessTheLeaseIsExpiringOrInvalid() async {
        api.update { $0.entitlementEnd = now + 90 * day }
        let service = service()
        _ = await service.signIn(email: "a@b.c", code: "1")
        let after = api.fetches
        setNow(now + 120)
        await service.refresh()
        #expect(api.fetches == after)
        setNow(now + 600)
        await service.refresh()
        #expect(api.fetches == after + 1)
    }

    @Test func aDeviceClockBehindTheBackendTurnsKeepPlusOffWithAClearReason() async {
        api.update { $0.entitlementEnd = now + 90 * day; $0.issuedAtOverride = now + 2 * 3_600 }
        let service = service()
        _ = await service.signIn(email: "a@b.c", code: "1")
        #expect(!service.access.plusFeatures && service.problem == .clockOff)
    }

    @Test func aWrongCodeAndARateLimitAreReportedWithoutTouchingTheAccount() async {
        api.update { $0.codeValid = false }
        let service = service()
        #expect(await service.signIn(email: "a@b.c", code: "0") == .invalidCode && service.account == nil)
        api.update { $0.failure = .rateLimited }
        #expect(await service.requestCode(email: "a@b.c") == .rateLimited)
    }
}
