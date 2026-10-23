package app.vazie.keep.autofill

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import android.view.autofill.AutofillManager

enum class AutofillStatus { Enabled, Disabled, NotAvailable }

// The system side of switching Autofill on: the documented request intent, never a private one.
interface AutofillSystem {
    fun status(): AutofillStatus

    fun enableIntent(): Intent
}

class AndroidAutofillSystem(private val context: Context) : AutofillSystem {
    override fun status(): AutofillStatus {
        val manager = context.getSystemService(AutofillManager::class.java)
        return when {
            manager == null || !manager.isAutofillSupported -> AutofillStatus.NotAvailable
            // True only when the calling app's own service is the enabled one.
            manager.hasEnabledAutofillServices() -> AutofillStatus.Enabled
            else -> AutofillStatus.Disabled
        }
    }

    override fun enableIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, "package:${context.packageName}".toUri())
}
