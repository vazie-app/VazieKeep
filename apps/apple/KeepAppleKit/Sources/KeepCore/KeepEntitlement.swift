import Foundation
import KeepCoreBindings

/// A public key the app trusts for Keep+ leases. `publicKey` is the raw 32-byte Ed25519 key, base64url.
public struct LeaseKey: Equatable, Sendable {
    public let kid: String
    public let publicKey: String

    public init(kid: String, publicKey: String) {
        self.kid = kid
        self.publicKey = publicKey
    }
}

public enum LeaseTier: Equatable, Sendable { case free, keepPlus }

public struct KeepLease: Equatable, Sendable {
    public let subject: String
    public let tier: LeaseTier
    public let lifetime: Bool
    public let issuedAt: Int64
    public let leaseExpiresAt: Int64
    public let entitlementExpiresAt: Int64?
    public let featuresVersion: UInt32
    public let revision: UInt64
    public let nonce: String
    public let keyId: String
}

public enum KeepAccessState: Equatable, Sendable {
    case free, plusActive, plusOfflineGrace, plusLifetime, plusExpired
}

public struct KeepAccess: Equatable, Sendable {
    public let state: KeepAccessState
    public let plusFeatures: Bool
    public let needsOnlineVerification: Bool
    public let leaseValidUntil: Int64?
    public let observedDeviceTime: Int64

    public init(state: KeepAccessState, plusFeatures: Bool, needsOnlineVerification: Bool, leaseValidUntil: Int64?, observedDeviceTime: Int64) {
        self.state = state
        self.plusFeatures = plusFeatures
        self.needsOnlineVerification = needsOnlineVerification
        self.leaseValidUntil = leaseValidUntil
        self.observedDeviceTime = observedDeviceTime
    }

    public static let free = KeepAccess(state: .free, plusFeatures: false, needsOnlineVerification: false, leaseValidUntil: nil, observedDeviceTime: 0)
}

/// Features that need Keep+. Anything that protects passwords is free and is deliberately not here.
public enum PlusFeature: Sendable, CaseIterable {
    case multiVault, totp, passwordHealth, breachMonitoring, historyUI, attachmentWrite, premiumThemes
}

public enum LeaseVerifyFailure: Error, Equatable, Sendable {
    case malformed, unsupportedAlgorithm, unknownKey, badSignature, unsupported, inconsistent
}

/// The Rust core's Keep+ rules, behind a protocol so app logic is tested against the real verifier.
public protocol KeepEntitlementCore: Sendable {
    /// A lease that does not verify must be treated as no lease at all.
    func verifyLease(_ token: String, keys: [LeaseKey]) throws(LeaseVerifyFailure) -> KeepLease
    func evaluateAccess(lease: KeepLease?, now: Int64, lastObserved: Int64) -> KeepAccess
    func canUse(_ feature: PlusFeature, access: KeepAccess) -> Bool
}

extension RustKeepCore: KeepEntitlementCore {
    public func verifyLease(_ token: String, keys: [LeaseKey]) throws(LeaseVerifyFailure) -> KeepLease {
        do {
            return try verifyKeepLease(token: token, keys: keys.map { TrustedKeyDto(kid: $0.kid, publicKey: $0.publicKey) }).lease
        } catch let error as KeepCoreBindings.LeaseVerifyError {
            throw switch error {
            case .Malformed: .malformed
            case .UnsupportedAlgorithm: .unsupportedAlgorithm
            case .UnknownKey: .unknownKey
            case .BadSignature: .badSignature
            case .Unsupported: .unsupported
            case .Inconsistent: .inconsistent
            }
        } catch {
            throw .malformed
        }
    }

    public func evaluateAccess(lease: KeepLease?, now: Int64, lastObserved: Int64) -> KeepAccess {
        evaluateKeepAccess(lease: lease?.dto, now: now, lastObserved: lastObserved).access
    }

    public func canUse(_ feature: PlusFeature, access: KeepAccess) -> Bool {
        canUsePlusFeature(feature: feature.dto, access: access.dto)
    }
}

private extension LeaseDto {
    var lease: KeepLease {
        KeepLease(
            subject: subject, tier: tier == .free ? .free : .keepPlus, lifetime: lifetime, issuedAt: issuedAt,
            leaseExpiresAt: leaseExpiresAt, entitlementExpiresAt: entitlementExpiresAt, featuresVersion: featuresVersion,
            revision: revision, nonce: nonce, keyId: keyId
        )
    }
}

private extension KeepLease {
    var dto: LeaseDto {
        LeaseDto(
            subject: subject, tier: tier == .free ? .free : .keepPlus, lifetime: lifetime, issuedAt: issuedAt,
            leaseExpiresAt: leaseExpiresAt, entitlementExpiresAt: entitlementExpiresAt, featuresVersion: featuresVersion,
            revision: revision, nonce: nonce, keyId: keyId
        )
    }
}

private extension AccessDto {
    var access: KeepAccess {
        let mapped: KeepAccessState = switch state {
        case .free: .free
        case .plusActive: .plusActive
        case .plusOfflineGrace: .plusOfflineGrace
        case .plusLifetime: .plusLifetime
        case .plusExpired: .plusExpired
        }
        return KeepAccess(
            state: mapped,
            plusFeatures: plusFeatures, needsOnlineVerification: needsOnlineVerification,
            leaseValidUntil: leaseValidUntil, observedDeviceTime: observedDeviceTime
        )
    }
}

extension KeepAccess {
    var dto: AccessDto {
        let mapped: AccessStateDto = switch state {
        case .free: .free
        case .plusActive: .plusActive
        case .plusOfflineGrace: .plusOfflineGrace
        case .plusLifetime: .plusLifetime
        case .plusExpired: .plusExpired
        }
        return AccessDto(
            state: mapped,
            plusFeatures: plusFeatures, needsOnlineVerification: needsOnlineVerification,
            leaseValidUntil: leaseValidUntil, observedDeviceTime: observedDeviceTime
        )
    }
}

private extension PlusFeature {
    var dto: PlusFeatureDto {
        switch self {
        case .multiVault: .multiVault
        case .totp: .totp
        case .passwordHealth: .passwordHealth
        case .breachMonitoring: .breachMonitoring
        case .historyUI: .historyUi
        case .attachmentWrite: .attachmentWrite
        case .premiumThemes: .premiumThemes
        }
    }
}
