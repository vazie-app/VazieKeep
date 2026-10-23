package app.vazie.keep.data

import app.vazie.keep.core.KeepAccess
import app.vazie.keep.core.KeepAccessState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// What the account allows for the list of vaults. `Unverified` is a Keep+ lease that ran out and has
// not been checked with the backend yet: Keep+ is off, but nothing permanent is asked of the person.
enum class VaultPlan { Plus, Free, Unverified }

fun KeepAccess.vaultPlan(): VaultPlan = when {
    plusFeatures -> VaultPlan.Plus
    state == KeepAccessState.Free -> VaultPlan.Free
    else -> VaultPlan.Unverified
}

// The rule for several vaults without Keep+: every vault can be opened and read, one of them — the main
// vault — can be edited, and no vault can be added. The main vault is chosen once and stays.
data class VaultRules(
    // Null means every vault.
    val editable: Set<String>?,
    val canAdd: Boolean,
    val mustChooseMain: Boolean,
    val mainId: String?,
) {
    fun canEdit(vaultId: String): Boolean = editable == null || vaultId in editable

    companion object {
        // `fingerprints` names the database behind a vault, where it is known. Several files of one
        // database (the same file opened twice, a copy of it) count as one vault.
        fun of(plan: VaultPlan, vaultIds: List<String>, storedMain: String?, fingerprints: Map<String, String> = emptyMap()): VaultRules {
            val main = storedMain?.takeIf { it in vaultIds }
            if (plan == VaultPlan.Plus) return VaultRules(editable = null, canAdd = true, mustChooseMain = false, mainId = main)
            val database = { id: String -> fingerprints[id] ?: id }
            if (vaultIds.map(database).distinct().size <= 1) return VaultRules(editable = null, canAdd = vaultIds.isEmpty(), mustChooseMain = false, mainId = main)
            val same = { id: String -> vaultIds.filterTo(HashSet()) { database(it) == database(id) } }
            return when (plan) {
                VaultPlan.Free -> VaultRules(editable = main?.let(same).orEmpty(), canAdd = false, mustChooseMain = main == null, mainId = main)
                // Until the backend answers, the first vault stands in for a main one that was never chosen.
                else -> VaultRules(editable = same(main ?: vaultIds.first()), canAdd = false, mustChooseMain = false, mainId = main)
            }
        }
    }
}

interface MainVaultStore {
    fun load(): String?

    fun save(id: String)
}

// The id is local and random; it says nothing about the vault.
class FileMainVaultStore(private val file: File) : MainVaultStore {
    override fun load(): String? = try {
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    }

    override fun save(id: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(id)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("could not store the main vault")
        }
    }
}

class VaultPolicy(
    private val registry: VaultRegistry,
    private val store: MainVaultStore,
    private val plan: () -> VaultPlan,
) {
    private var main: String? = store.load()
    private val mutable = MutableStateFlow(evaluate())
    val rules: StateFlow<VaultRules> = mutable.asStateFlow()

    private fun evaluate(): VaultRules {
        val vaults = registry.snapshot.value.vaults
        val fingerprints = buildMap { vaults.forEach { vault -> vault.fingerprint?.let { put(vault.id, it) } } }
        return VaultRules.of(plan(), vaults.map { it.id }, main, fingerprints)
    }

    // Call when the plan or the list of vaults changes.
    fun refresh(): VaultRules = evaluate().also { mutable.value = it }

    fun current(): VaultRules = refresh()

    // A list entry turned out to be another vault of the list: a choice made for it carries over.
    @Synchronized
    fun merged(removed: String, into: String) {
        if (main == removed) {
            store.save(into)
            main = into
        }
        refresh()
    }

    // Only answers while the choice is being asked for; afterwards the main vault stays what it is.
    @Synchronized
    fun chooseMain(id: String): Boolean {
        if (!refresh().mustChooseMain || registry.find(id) == null) return false
        store.save(id)
        main = id
        refresh()
        return true
    }
}
