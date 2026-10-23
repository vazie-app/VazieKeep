import AuthenticationServices
import KeepApp
import KeepCore
import KeepDesignSystem
import os
import SwiftUI
import UIKit

/// Each request gets a fresh flow, controller and Rust session in this process. Nothing is
/// shared with the app's own session; the vault is opened here and closed when the request ends.
final class CredentialProviderViewController: ASCredentialProviderViewController, AutoFillCompletion {
    private var flow: AutoFillFlow?
    private var observers: [NSObjectProtocol] = []

    override func prepareCredentialList(for serviceIdentifiers: [ASCredentialServiceIdentifier]) {
        start(.list(serviceIdentifiers.compactMap(Self.origin)))
    }

    /// A site that takes a passkey: its passkeys are offered next to the matching logins.
    override func prepareCredentialList(for serviceIdentifiers: [ASCredentialServiceIdentifier], requestParameters: ASPasskeyCredentialRequestParameters) {
        let query = PasskeyQuery(rpID: requestParameters.relyingPartyIdentifier, clientDataHash: requestParameters.clientDataHash, allowedCredentials: requestParameters.allowedCredentials)
        start(.passkeyList(serviceIdentifiers.compactMap(Self.origin), query))
    }

    override func prepareInterface(forPasskeyRegistration registrationRequest: any ASCredentialRequest) {
        guard let request = registrationRequest as? ASPasskeyCredentialRequest, let identity = request.credentialIdentity as? ASPasskeyCredentialIdentity else {
            fail(.failed)
            return
        }
        start(.passkeyRegistration(PasskeyRegistrationQuery(
            rpID: identity.relyingPartyIdentifier,
            userName: identity.userName,
            userHandle: identity.userHandle,
            clientDataHash: request.clientDataHash,
            acceptsES256: request.supportedAlgorithms.isEmpty || request.supportedAlgorithms.contains(.ES256)
        )))
    }

    override func prepareInterfaceToProvideCredential(for credentialRequest: any ASCredentialRequest) {
        if let request = credentialRequest as? ASPasskeyCredentialRequest, let identity = request.credentialIdentity as? ASPasskeyCredentialIdentity {
            guard let record = identity.recordIdentifier else {
                fail(.credentialIdentityNotFound)
                return
            }
            let query = PasskeyQuery(rpID: identity.relyingPartyIdentifier, clientDataHash: request.clientDataHash, allowedCredentials: [identity.credentialID])
            start(.passkey(recordIdentifier: record, query))
            return
        }
        guard let identity = credentialRequest.credentialIdentity as? ASPasswordCredentialIdentity, let record = identity.recordIdentifier else {
            fail(.credentialIdentityNotFound)
            return
        }
        start(.credential(recordIdentifier: record, service: Self.origin(identity.serviceIdentifier)))
    }

    /// The vault is never kept open between requests, so there is nothing to fill silently.
    override func provideCredentialWithoutUserInteraction(for credentialRequest: any ASCredentialRequest) {
        fail(.userInteractionRequired)
    }

    override func prepareInterfaceForExtensionConfiguration() {
        let configured = FileVaultReferenceStore.shared().load() != nil
        show(AutoFillConfigurationView(configured: configured) { [weak self] in
            self?.extensionContext.completeExtensionConfigurationRequest()
        })
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        flow?.suspend()
    }

    // MARK: AutoFillCompletion

    func complete(user: String, password: String) {
        extensionContext.completeRequest(withSelectedCredential: ASPasswordCredential(user: user, password: password))
    }

    func complete(assertion: PasskeyAssertion, query: PasskeyQuery) {
        let credential = ASPasskeyAssertionCredential(
            userHandle: assertion.userHandle,
            relyingParty: query.rpID,
            signature: assertion.signature,
            clientDataHash: query.clientDataHash,
            authenticatorData: assertion.authenticatorData,
            credentialID: assertion.credentialID
        )
        extensionContext.completeAssertionRequest(using: credential)
    }

    func complete(registration: CreatedPasskey, query: PasskeyRegistrationQuery) {
        let credential = ASPasskeyRegistrationCredential(
            relyingParty: query.rpID,
            clientDataHash: query.clientDataHash,
            credentialID: registration.credentialID,
            attestationObject: registration.attestationObject
        )
        extensionContext.completeRegistrationRequest(using: credential)
    }

    func cancel() {
        fail(.userCanceled)
    }

    // MARK: Private

    private func start(_ request: AutoFillRequest) {
        let controller = VaultSessionController(references: FileVaultReferenceStore.shared(), settings: { SecuritySettings() }, timing: AutoFillDebug.timing)
        let biometric = BiometricUnlock(
            controller: controller,
            authenticator: LocalBiometricAuthenticator(fallbackTitle: String(localized: "Use Master Password")),
            credentials: KeychainCredentialStore(),
            setups: FileBiometricSetupStore.shared(),
            log: AutoFillDebug.event
        )
        let flow = AutoFillFlow(request: request, controller: controller, biometric: biometric, completion: self, log: AutoFillDebug.event)
        controller.onUnlock = { [weak flow] in flow?.vaultUnlocked() }
        self.flow = flow
        observeHost(flow)
        show(AutoFillRootView(flow: flow))
    }

    private func observeHost(_ flow: AutoFillFlow) {
        let center = NotificationCenter.default
        observers.forEach(center.removeObserver)
        observers = [
            center.addObserver(forName: .NSExtensionHostDidEnterBackground, object: nil, queue: .main) { [weak flow] _ in
                MainActor.assumeIsolated { flow?.suspend() }
            },
        ]
    }

    private func show(_ view: some View) {
        children.forEach { child in
            child.willMove(toParent: nil)
            child.view.removeFromSuperview()
            child.removeFromParent()
        }
        KeepFonts.register()
        let host = UIHostingController(rootView: view.tint(KeepColor.accent))
        addChild(host)
        host.view.frame = self.view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        self.view.addSubview(host.view)
        host.didMove(toParent: self)
    }

    private func fail(_ code: ASExtensionError.Code) {
        flow?.suspend()
        extensionContext.cancelRequest(withError: NSError(domain: ASExtensionErrorDomain, code: code.rawValue))
    }

    private static func origin(_ identifier: ASCredentialServiceIdentifier) -> ServiceOrigin? {
        switch identifier.type {
        case .domain: ServiceOrigin(serviceIdentifier: identifier.identifier, isDomain: true)
        case .URL: ServiceOrigin(serviceIdentifier: identifier.identifier, isDomain: false)
        default: nil
        }
    }
}

/// Debug builds only: counts and states, never a host, a title or a username.
enum AutoFillDebug {
    static func event(_ text: String) {
        #if DEBUG
        logger.debug("\(text, privacy: .public)")
        #endif
    }

    static func timing(_ label: String, _ duration: Duration) {
        #if DEBUG
        let millis = Double(duration.components.seconds) * 1000 + Double(duration.components.attoseconds) / 1e15
        logger.debug("\(label, privacy: .public): \(millis, format: .fixed(precision: 1), privacy: .public) ms")
        #endif
    }

    #if DEBUG
    private static let logger = Logger(subsystem: "app.vazie.keep.autofill", category: "perf")
    #endif
}
