package app.vazie.keep

import androidx.core.net.toUri
import app.vazie.keep.biometric.BiometricRecord
import app.vazie.keep.data.VaultState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.PrintWriter
import java.security.KeyStore

// Debug builds only (`adb shell dumpsys activity app.vazie.keep.debug/app.vazie.keep.MainActivity`). Prints state flags,
// never entry data, file paths or anything read from the vault.
internal object DebugState {
    fun write(container: AppContainer, writer: PrintWriter) {
        val settings = container.settings.value
        val reference = container.repository.reference()
        val readable = reference?.let {
            runBlocking { withTimeoutOrNull(2_000) { container.repository.isReferenceReadable() } }
        }
        writer.println("Vazie Keep debug state")
        writer.println("  session: ${if (container.repository.state.value is VaultState.Unlocked) "open" else "locked"}")
        writer.println("  auto-lock: ${settings.autoLockTimeout}, lock on device lock: ${settings.lockOnDeviceLock}")
        writer.println("  clipboard: ${settings.clipboardTimeout}, screenshots allowed: ${settings.screenshotsAllowed}, theme: ${settings.themeMode}")
        writer.println("  vault reference: ${reference?.let { "present (${it.uri.toUri().authority})" } ?: "none"}")
        writer.println("  vault file readable: ${readable ?: "n/a"}")
        writer.println("  key file reference: ${reference?.keyFileUri?.let { "present (${it.toUri().authority})" } ?: "none"}")
        val unlocked = container.repository.state.value as? VaultState.Unlocked
        writer.println("  unlocked with: ${unlocked?.let { "password=${it.usedPassword}, key file=${it.usedKeyFile}, material retained=${it.materialRetained}" } ?: "n/a"}")
        val capabilities = runBlocking { withTimeoutOrNull(2_000) { container.repository.providerCapabilities() } }
        writer.println("  save: unsaved changes=${container.repository.hasUnsavedChanges.value}, recovered copy=${container.repository.hasRecoveredCopy.value}, write access=${unlocked?.writeGranted ?: "n/a"}")
        writer.println("  provider: ${capabilities?.let { "${it.authority} write=${it.canWrite} delete=${it.canDelete} rename=${it.canRename} move=${it.canMove} virtual=${it.isVirtual}" } ?: "n/a"}")
        val index = container.autofillIndexStore.load()
        writer.println("  autofill: ${container.autofillSystem.status()}, index entries=${index?.entries?.size ?: "none"}, app choices=${container.appAssociations.all().size}")
        writer.println("  app icon: ${container.launcherIcons.current.value}")
        writer.println("  biometric: ${container.biometrics.status.value}, authenticator: ${container.biometricAuthenticator.availability()}")
        val record = container.biometricStore.load()
        val aliases = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList()
            .count { it.startsWith("vazie_keep_biometric_") }
        writer.println("  biometric record: ${record?.javaClass?.simpleName ?: "none"}, keystore keys: $aliases, record key present: ${(record as? BiometricRecord.Active)?.let { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(it.alias) } ?: "n/a"}")
    }
}
