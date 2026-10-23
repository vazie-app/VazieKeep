package app.vazie.keep.core

data class CoreInfo(val name: String, val version: String)

sealed interface VaultFormat {
    data class Kdbx(val major: Int, val minor: Int) : VaultFormat

    data object KeePass1 : VaultFormat

    data object Unknown : VaultFormat
}

interface KeepCore {
    fun info(): CoreInfo

    fun detectVaultFormat(header: ByteArray): VaultFormat

    // Runs the key derivation on a background dispatcher. Every credential array is wiped afterwards.
    suspend fun openVault(data: ByteArray, credentials: VaultCredentials): KeepVaultSession

    suspend fun openVault(data: ByteArray, password: ByteArray): KeepVaultSession =
        openVault(data, VaultCredentials(password = password))

    fun closeAllSessions(): Int

    // The shared Autofill origin policy: may an entry saved for `entryHost` be offered on `requestHost`?
    // Hosts are IDNA (punycode) encoded.
    fun matchHost(entryHost: String, requestHost: String): HostMatch

    fun generatePassword(options: PasswordOptions): SecretValue

    // Reads another password manager's export: CSV with a header row, 1Password (.1pux, CSV), Bitwarden (JSON, CSV).
    fun parseImport(data: ByteArray): ImportPlan

    // The two named key-derivation presets. The numbers come from the core, so they are the same
    // on every platform and a vault made on one phone opens in a comparable time on another.
    val balancedPreset: KdfSettings
    val strongerPreset: KdfSettings

    // Builds, encrypts and verifies a new KDBX 4.1 vault. The result is the complete file content.
    // Every credential array is wiped afterwards.
    suspend fun createVault(name: String, kdf: KdfSettings, credentials: VaultCredentials): CreatedVault

    // How long one key derivation with these settings takes on this device.
    suspend fun benchmarkKdf(kdf: KdfSettings): Int

    // A new KeePass XML key file (version 2) made from the system CSPRNG.
    fun generateKeyFile(): SecretValue

    fun normalizeTotp(input: ByteArray, access: KeepAccess): TotpSetup

    // Checks the signature and shape of a Keep+ lease against the keys the app trusts. Throws
    // LeaseVerifyException; a lease that does not verify must be treated as no lease at all.
    fun verifyLease(token: String, keys: List<LeaseKey>): KeepLease

    // What a verified lease allows at device time `now` (Unix seconds), given the latest device time seen.
    fun evaluateAccess(lease: KeepLease?, now: Long, lastObserved: Long): KeepAccess

    fun canUse(feature: PlusFeature, access: KeepAccess): Boolean
}

data class KdfSettings(val memoryKib: Int, val iterations: Int, val parallelism: Int)

class CreatedVault(val bytes: ByteArray, val fingerprint: String) {
    override fun toString() = "CreatedVault(bytes=${bytes.size})"
}

class VaultCreateException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { EmptyName, InvalidName, InvalidSettings, MissingCredentials, InvalidKeyFile, Unavailable }
}

enum class HostMatch { Exact, Subdomain, None }

class VaultOpenException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        NotAVault,
        Unsupported,
        MissingCredentials,
        InvalidCredentials,
        InvalidKeyFile,
        Damaged,
        TooLarge,
        TooManySessions,
        Unavailable,
    }
}
