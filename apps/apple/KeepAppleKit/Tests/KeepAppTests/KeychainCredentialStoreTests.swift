import Foundation
import KeepApp
import LocalAuthentication
import Security
import Testing

private let keychainWithBiometryAvailable: Bool = {
    let probe: [CFString: Any] = [
        kSecClass: kSecClassGenericPassword,
        kSecAttrService: "app.vazie.keep.tests.probe",
        kSecAttrAccount: "probe",
        kSecUseDataProtectionKeychain: true,
        kSecValueData: Data([0]),
    ]
    SecItemDelete(probe as CFDictionary)
    let status = SecItemAdd(probe as CFDictionary, nil)
    SecItemDelete(probe as CFDictionary)
    return status == errSecSuccess && LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
}()

/// The real Keychain with the production access control (`biometryCurrentSet`). Reading the
/// secret needs a face, so these tests check everything around it: the item exists, its data is
/// refused without Face ID, its attributes, the installation binding and the sweep. They run in
/// the app-hosted test bundle on the iPhone; `swift test` on the Mac has no data-protection
/// Keychain and skips them.
@Suite(.enabled(if: keychainWithBiometryAvailable))
struct KeychainCredentialStoreTests {
    let service = "app.vazie.keep.tests.\(UUID().uuidString)"
    let installation = Data(repeating: 1, count: 16)
    var store: KeychainCredentialStore { KeychainCredentialStore(service: service) }

    private func create(_ id: String, installation: Data? = nil) throws {
        try store.create(id: id, installation: installation ?? self.installation, material: Data(repeating: 5, count: 33), authorization: BiometricAuthorization(context: nil))
    }

    /// Existence of a matching item, without Face ID: "interaction not allowed" means it exists.
    private func matches(_ attributes: [CFString: Any]) -> OSStatus {
        let context = LAContext()
        context.interactionNotAllowed = true
        var query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecUseDataProtectionKeychain: true,
            kSecUseAuthenticationContext: context,
        ]
        attributes.forEach { query[$0.key] = $0.value }
        return SecItemCopyMatching(query as CFDictionary, nil)
    }

    @Test func theSecretIsRefusedWithoutFaceID() throws {
        defer { store.removeAll() }
        try create("a")
        #expect(store.contains(id: "a", installation: installation))
        #expect(throws: BiometricStoreError.interactionNotAllowed) {
            try store.read(id: "a", installation: installation, authorization: BiometricAuthorization(context: nil))
        }
    }

    @Test func theItemIsLocalPasscodeBoundAndNeverSynchronized() throws {
        defer { store.removeAll() }
        try create("a")
        let account: [CFString: Any] = [kSecAttrAccount: "a"]
        #expect(matches(account.merging([kSecAttrAccessible: kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly]) { $1 }) == errSecInteractionNotAllowed)
        #expect(matches(account.merging([kSecAttrAccessible: kSecAttrAccessibleWhenUnlocked]) { $1 }) == errSecItemNotFound)
        #expect(matches(account.merging([kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]) { $1 }) == errSecItemNotFound)
        #expect(matches(account.merging([kSecAttrSynchronizable: true]) { $1 }) == errSecItemNotFound)
        #expect(matches(account.merging([kSecAttrSynchronizable: false]) { $1 }) == errSecInteractionNotAllowed)
    }

    @Test func anotherInstallationsItemIsNotTrustedAndIsSwept() throws {
        defer { store.removeAll() }
        try create("old", installation: Data(repeating: 2, count: 16))
        #expect(!store.contains(id: "old", installation: installation))
        #expect(throws: BiometricStoreError.notFound) {
            try store.read(id: "old", installation: installation, authorization: BiometricAuthorization(context: nil))
        }
        #expect(store.removeAll())
        #expect(!store.contains(id: "old", installation: Data(repeating: 2, count: 16)))
        #expect(!store.removeAll())
    }

    @Test func removingOneSetupLeavesTheOther() throws {
        defer { store.removeAll() }
        try create("a")
        try create("b")
        store.remove(id: "a")
        #expect(!store.contains(id: "a", installation: installation))
        #expect(store.contains(id: "b", installation: installation))
    }

    @Test func newItemsLiveInTheGroupSharedWithTheExtensionOnly() throws {
        defer { store.removeAll() }
        let shared = try #require(KeychainCredentialStore.sharedAccessGroup)
        #expect(shared.hasSuffix(".app.vazie.keep.shared"))
        try create("a")
        let appOnly = shared.replacingOccurrences(of: ".app.vazie.keep.shared", with: ".app.vazie.keep")
        #expect(matches([kSecAttrAccount: "a", kSecAttrAccessGroup: shared]) == errSecInteractionNotAllowed)
        #expect(matches([kSecAttrAccount: "a", kSecAttrAccessGroup: appOnly]) == errSecItemNotFound)
    }
}
