import KeepApp
import KeepCore
import KeepDesignSystem
import SwiftUI

struct AutoFillRootView: View {
    let flow: AutoFillFlow

    var body: some View {
        NavigationStack {
            Group {
                switch flow.phase {
                case .failed(.notConfigured):
                    AutoFillMessage(symbol: "lock.doc", text: String(localized: "Open Vazie Keep first and choose a vault."))
                case .failed(.passkeyNotSaved):
                    AutoFillMessage(symbol: "exclamationmark.triangle", text: String(localized: "The passkey couldn't be saved in the vault, so the site didn't get one. Open Vazie Keep and check that the vault can be saved."))
                case .failed(.passkeyUnsupported):
                    AutoFillMessage(symbol: "exclamationmark.triangle", text: String(localized: "The site asks for a kind of passkey Vazie Keep doesn't make."))
                case .failed(.suggestionOutdated), .locked:
                    AutoFillUnlockView(flow: flow)
                case .choosing where flow.isRegistering:
                    VStack(spacing: 16) {
                        ProgressView()
                        Text("Saving the passkey in your vault…").keepText(.body, color: KeepColor.textSecondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                case .choosing:
                    AutoFillEntryList(flow: flow)
                case .done:
                    Color.clear
                }
            }
            .keepScreen()
            .navigationTitle("Vazie Keep")
            .toolbarBackground(.hidden, for: .navigationBar)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { flow.cancel() }
                        .buttonStyle(.keep(.text, size: .pill, fullWidth: false))
                        .accessibilityIdentifier("autofillCancel")
                }
            }
        }
    }
}

/// Face ID first when it is set up, the master password always. A key file can't be chosen
/// here: the extension reads the one chosen in the app, every time.
struct AutoFillUnlockView: View {
    let flow: AutoFillFlow
    @State private var model: UnlockModel
    @FocusState private var passwordFocused: Bool

    init(flow: AutoFillFlow) {
        self.flow = flow
        _model = State(initialValue: UnlockModel(controller: flow.controller, biometric: flow.biometric))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if flow.notice == .suggestionOutdated {
                    KeepBanner(String(localized: "This saved login changed. Unlock and choose an entry."))
                }
                Spacer(minLength: 8)
                KeepMonogram(model.vaultName ?? String(localized: "Vault"), size: 88, radius: 28, tone: KeepColor.vaultTiles[0], fontSize: 38)
                Text(model.vaultName ?? String(localized: "Vault"))
                    .keepText(.headline)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                if let keyFile = model.keyFileName {
                    HStack(spacing: 8) {
                        Image(systemName: "key").font(.system(size: 13))
                        Text(String(localized: "Key file: \(keyFile)")).keepText(.mono, color: KeepColor.text).lineLimit(1).truncationMode(.middle)
                    }
                    .foregroundStyle(KeepColor.textSecondary)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 36)
                    .background(KeepColor.surfaceField, in: .rect(cornerRadius: KeepRadius.chip, style: .continuous))
                }
                if let message = message {
                    KeepBanner(message.text, tone: message.isError ? .error : .info)
                        .accessibilityIdentifier("autofillError")
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 12)
        }
        .scrollDismissesKeyboard(.never)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 12) {
                if model.offersBiometricUnlock {
                    Button {
                        passwordFocused = false
                        Task { await model.unlockWithBiometrics(reason: String(localized: "Fill a password from your vault")) }
                    } label: {
                        Label("Unlock with Face ID", systemImage: "faceid")
                    }
                    .buttonStyle(.keep(.action))
                    .disabled(model.isUnlocking)
                    .accessibilityIdentifier("autofillFaceID")
                }
                KeepFieldChrome(label: nil, focused: passwordFocused, height: 64) {
                    SecureField("Master password", text: $model.password, prompt: Text("Master password").foregroundStyle(KeepColor.textTertiary)).font(.system(size: 17)).foregroundStyle(Color(uiColor: .label))
                        .focused($passwordFocused)
                        .submitLabel(.go)
                        .onSubmit { Task { await model.unlock() } }
                        .privacySensitive()
                        .accessibilityIdentifier("autofillPassword")
                } trailing: {
                    Button {
                        Task { await model.unlock() }
                    } label: {
                        if model.isUnlocking { ProgressView().tint(KeepColor.onAction) } else { Image(systemName: "arrow.right") }
                    }
                    .buttonStyle(.keepIcon(.action, size: 48, radius: KeepRadius.innerButton))
                    .disabled(!model.canUnlock || model.isUnlocking)
                    .accessibilityLabel(Text("Unlock"))
                    .accessibilityIdentifier("autofillUnlock")
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 12)
            .background(KeepColor.background)
        }
        .onAppear { if !model.offersBiometricUnlock { passwordFocused = true } }
        .onChange(of: model.refocusSignal) { passwordFocused = true }
        .onDisappear { model.discardPassword() }
    }

    private var message: (text: String, isError: Bool)? {
        if let error = model.error {
            switch error {
            case .keyFileUnavailable, .keyFileRequired, .keyFileTooLarge, .invalidKeyFile:
                return (String(localized: "Open Vazie Keep and choose the key file again."), true)
            case .fileUnavailable:
                return (String(localized: "The vault can't be read. Open Vazie Keep and choose it again."), true)
            default:
                return (error.message, true)
            }
        }
        if let result = model.biometricResult {
            if result == .keyFileRequired { return (String(localized: "Open Vazie Keep and choose the key file again."), true) }
            if let text = result.message { return (text, result.isError) }
        }
        if model.biometricNeedsSetup {
            return (String(localized: "Face ID unlock needs to be set up again in Vazie Keep. Use your master password."), false)
        }
        return nil
    }
}

struct AutoFillEntryList: View {
    @Bindable var flow: AutoFillFlow

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                KeepTextField(String(localized: "Search the vault"), text: $flow.query, height: 52, leadingSymbol: "magnifyingglass")
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.bottom, 4)
                if let results = flow.searchResults {
                    KeepSectionLabel(String(localized: "Search results"))
                    if results.isEmpty {
                        Text("No matching entries").keepText(.body, color: KeepColor.textSecondary).padding(.top, 24)
                    } else {
                        card(results)
                    }
                } else {
                    if flow.notice == .suggestionOutdated {
                        KeepBanner(String(localized: "This saved login changed. Choose an entry."))
                    }
                    if !flow.passkeys.isEmpty {
                        KeepSectionLabel(String(localized: "Passkeys"))
                        KeepCard {
                            ForEach(Array(flow.passkeys.enumerated()), id: \.element.id) { index, entry in
                                if index > 0 { KeepHairline(inset: 70) }
                                passkeyRow(entry)
                            }
                        }
                    }
                    KeepSectionLabel(String(localized: "Suggested"))
                    if flow.suggested.isEmpty {
                        Text("Nothing saved for this site").keepText(.rowSubtitle, color: KeepColor.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 4)
                    } else {
                        card(flow.suggested)
                    }
                    if !flow.others.isEmpty {
                        KeepSectionLabel(String(localized: "Other entries"))
                        card(flow.others)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .task(id: flow.query) {
            try? await Task.sleep(for: .milliseconds(200))
            await flow.search()
        }
    }

    private func card(_ entries: [EntrySummary]) -> some View {
        KeepCard {
            ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                if index > 0 { KeepHairline(inset: 70) }
                row(entry)
            }
        }
    }

    private func passkeyRow(_ entry: EntrySummary) -> some View {
        Button {
            flow.usePasskey(entry.id)
        } label: {
            HStack(spacing: 14) {
                KeepMonogram(entry.displayTitle.isEmpty ? "·" : entry.displayTitle)
                VStack(alignment: .leading, spacing: 3) {
                    Text(entry.displayTitle).keepText(.rowTitle).lineLimit(1)
                    Text(entry.passkey?.userName ?? "").keepText(.rowSubtitle, color: KeepColor.textSecondary).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "person.badge.key").foregroundStyle(KeepColor.textSecondary).accessibilityHidden(true)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(minHeight: 64)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint(Text("Signs in with this passkey"))
        .accessibilityIdentifier("autofill.passkey.\(entry.id.rawValue)")
    }

    private func row(_ entry: EntrySummary) -> some View {
        Button {
            flow.choose(entry.id)
        } label: {
            HStack(spacing: 14) {
                KeepMonogram(entry.displayTitle.isEmpty ? "·" : entry.displayTitle)
                VStack(alignment: .leading, spacing: 3) {
                    Text(entry.displayTitle).keepText(.rowTitle).lineLimit(1)
                    if case let .plain(user) = entry.username, !user.isEmpty {
                        Text(user).keepText(.rowSubtitle, color: KeepColor.textSecondary).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(minHeight: 64)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint(Text("Fills this login"))
        .accessibilityIdentifier("autofill.\(entry.id.rawValue)")
    }
}

struct AutoFillMessage: View {
    let symbol: String
    let text: String

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: symbol)
                .font(.system(size: 32))
                .foregroundStyle(KeepColor.onAccentContainer)
                .frame(width: 72, height: 72)
                .background(KeepColor.accentContainer, in: .rect(cornerRadius: KeepRadius.hero, style: .continuous))
                .accessibilityHidden(true)
            Text(text)
                .keepText(.body, color: KeepColor.textSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct AutoFillConfigurationView: View {
    let configured: Bool
    let done: () -> Void

    var body: some View {
        NavigationStack {
            AutoFillMessage(
                symbol: configured ? "checkmark.circle" : "lock.doc",
                text: configured
                    ? String(localized: "Vazie Keep can now fill passwords. Unlock your vault when a login form asks.")
                    : String(localized: "Open Vazie Keep first and choose a vault.")
            )
            .keepScreen()
            .navigationTitle("Vazie Keep")
            .toolbarBackground(.hidden, for: .navigationBar)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done", action: done).buttonStyle(.keep(.action, size: .pill, fullWidth: false)) } }
        }
    }
}
