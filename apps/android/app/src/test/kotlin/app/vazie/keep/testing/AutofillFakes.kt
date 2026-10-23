package app.vazie.keep.testing

import app.vazie.keep.autofill.AppAssociation
import app.vazie.keep.autofill.AppAssociationStore
import app.vazie.keep.autofill.AutofillIndex
import app.vazie.keep.autofill.AutofillIndexStore
import app.vazie.keep.biometric.BiometricAvailability
import app.vazie.keep.biometric.BiometricMaterialSource
import app.vazie.keep.biometric.BiometricRecord
import app.vazie.keep.biometric.BiometricStatus
import app.vazie.keep.biometric.BiometricUnlockResult
import app.vazie.keep.biometric.MaterialResult
import app.vazie.keep.biometric.PromptText

class MemoryIndexStore(var index: AutofillIndex?) : AutofillIndexStore {
    override fun load() = index

    override fun save(index: AutofillIndex) {
        this.index = index
    }

    override fun clear() {
        index = null
    }
}

class MemoryAssociations(var list: List<AppAssociation> = emptyList()) : AppAssociationStore {
    override fun all() = list

    override fun add(association: AppAssociation) {
        list = list.filterNot { it.packageName == association.packageName && it.entryId == association.entryId } + association
    }

    override fun retain(fingerprint: String, existingEntryIds: Set<String>) {
        list = list.filter { it.fingerprint == fingerprint && it.entryId in existingEntryIds }
    }

    override fun clear() {
        list = emptyList()
    }
}

class FakeBiometricSource(var enabled: Boolean = false, var keyFileRequired: Boolean = false) : BiometricMaterialSource {
    val results = ArrayDeque<MaterialResult>()
    var prompts = 0
    var rejected = 0
    val record get() = BiometricRecord.Active(VAULT_URI, FINGERPRINT, "alias", keyFileRequired, ByteArray(0), ByteArray(0))

    override suspend fun refresh(): BiometricStatus =
        if (enabled) BiometricStatus.On(keyFileRequired) else BiometricStatus.Unavailable(BiometricAvailability.NoneEnrolled)

    override suspend fun obtainMaterial(text: PromptText): MaterialResult {
        prompts++
        return results.removeFirstOrNull() ?: MaterialResult.Cancelled
    }

    override suspend fun materialRejected(record: BiometricRecord.Active): BiometricUnlockResult {
        rejected++
        return BiometricUnlockResult.NeedsSetup
    }
}
