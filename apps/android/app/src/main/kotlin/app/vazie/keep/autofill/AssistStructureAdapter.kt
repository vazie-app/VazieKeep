package app.vazie.keep.autofill

import android.app.assist.AssistStructure
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.autofill.AutofillId
import java.security.MessageDigest

// The framework's view of a screen, flattened into what FormParser reads.
class ScreenForm(val packageName: String, val nodes: List<FormNode<AutofillId>>)

object AssistStructureAdapter {
    private const val MAX_NODES = 3000

    fun read(structure: AssistStructure, withValues: Boolean = false): ScreenForm? {
        // The activity component is set by the system, not by the app, so the package can't be faked.
        val packageName = structure.activityComponent?.packageName ?: return null
        val nodes = ArrayList<FormNode<AutofillId>>()
        val pending = ArrayDeque<AssistStructure.ViewNode>()
        for (i in 0 until structure.windowNodeCount) pending.addLast(structure.getWindowNodeAt(i).rootViewNode)
        while (pending.isNotEmpty() && nodes.size < MAX_NODES) {
            val node = pending.removeFirst()
            for (i in 0 until node.childCount) pending.addLast(node.getChildAt(i))
            nodes += convert(node, withValues) ?: continue
        }
        return ScreenForm(packageName, nodes)
    }

    private fun convert(node: AssistStructure.ViewNode, withValues: Boolean): FormNode<AutofillId>? {
        val id = node.autofillId ?: return null
        val attributes = node.htmlInfo?.attributes.orEmpty().associate { it.first.lowercase() to it.second }
        return FormNode(
            id = id,
            hints = node.autofillHints?.toList().orEmpty(),
            inputType = node.inputType,
            isText = node.autofillType == View.AUTOFILL_TYPE_TEXT,
            isVisible = node.visibility == View.VISIBLE,
            isFocused = node.isFocused,
            htmlTag = node.htmlInfo?.tag,
            htmlType = attributes["type"],
            htmlName = attributes["name"],
            htmlId = attributes["id"],
            htmlAutocomplete = attributes["autocomplete"],
            resourceEntry = node.idEntry,
            hintText = node.hint,
            webDomain = node.webDomain?.takeIf { it.isNotBlank() },
            webScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.webScheme else null,
            value = if (withValues) node.autofillValue?.takeIf { it.isText }?.textValue else null,
        )
    }
}

class AndroidPackageIdentity(private val context: Context) : PackageIdentity {
    @Suppress("DEPRECATION")
    override fun signing(packageName: String): SigningIdentity? = try {
        val manager = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            when {
                info == null -> null
                info.hasMultipleSigners() -> identityOf(info.apkContentsSigners.map { digest(it) }.sorted().joinToString(","))
                else -> {
                    val history = info.signingCertificateHistory.orEmpty().map { digest(it) }
                    val current = history.lastOrNull() ?: info.apkContentsSigners.singleOrNull()?.let(::digest)
                    current?.let { SigningIdentity(it, history.dropLast(1).toSet()) }
                }
            }
        } else {
            identityOf(manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.map { digest(it) }?.sorted()?.joinToString(","))
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: RuntimeException) {
        null
    }.also { found ->
        if (app.vazie.keep.BuildConfig.DEBUG) {
            android.util.Log.d("VazieKeepPerf", "certificate lookup visible=${found != null} pinnedBrowserName=${packageName in PINNED_NAMES}")
        }
    }

    override fun label(packageName: String): String? = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun identityOf(digest: String?) = digest?.takeIf { it.isNotEmpty() }?.let { SigningIdentity(it) }

    private fun digest(signature: android.content.pm.Signature) = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val PINNED_NAMES = TrustedBrowsers.Default.packages
    }
}
