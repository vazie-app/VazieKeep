package app.vazie.keep.data

import android.content.Context
import androidx.core.content.edit

data class VaultReference(
    val uri: String,
    val displayName: String,
    val lastUsedAt: Long,
    val keyFileUri: String? = null,
    val keyFileName: String? = null,
    // The local, stable id of this vault in the registry. Everything kept per vault is keyed by it.
    val id: String = "",
    // The name of the file as the provider reports it; displayName is the label the person sees.
    val fileName: String = displayName,
)

// "Passwords.kdbx" -> "Passwords copy.kdbx"
fun VaultReference.copyFileName(): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot > 0) fileName.substring(0, dot) + " copy" + fileName.substring(dot) else "$fileName copy"
}

interface VaultReferenceStore {
    fun load(): VaultReference?

    fun save(reference: VaultReference)

    fun clear()
}

class SharedPreferencesVaultReferenceStore(context: Context) : VaultReferenceStore {
    private val preferences = context.getSharedPreferences("vault_reference", Context.MODE_PRIVATE)

    override fun load(): VaultReference? {
        val uri = preferences.getString(KEY_URI, null) ?: return null
        return VaultReference(
            uri = uri,
            displayName = preferences.getString(KEY_NAME, null).orEmpty(),
            lastUsedAt = preferences.getLong(KEY_LAST_USED, 0),
            keyFileUri = preferences.getString(KEY_KEY_FILE_URI, null),
            keyFileName = preferences.getString(KEY_KEY_FILE_NAME, null),
        )
    }

    override fun save(reference: VaultReference) {
        preferences.edit {
            putString(KEY_URI, reference.uri)
            putString(KEY_NAME, reference.displayName)
            putLong(KEY_LAST_USED, reference.lastUsedAt)
            putString(KEY_KEY_FILE_URI, reference.keyFileUri)
            putString(KEY_KEY_FILE_NAME, reference.keyFileName)
        }
    }

    override fun clear() {
        preferences.edit { clear() }
    }

    private companion object {
        const val KEY_URI = "uri"
        const val KEY_NAME = "display_name"
        const val KEY_LAST_USED = "last_used_at"
        const val KEY_KEY_FILE_URI = "key_file_uri"
        const val KEY_KEY_FILE_NAME = "key_file_display_name"
    }
}
