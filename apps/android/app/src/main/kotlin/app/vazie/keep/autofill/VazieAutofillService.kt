package app.vazie.keep.autofill

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import app.vazie.keep.KeepApplication
import app.vazie.keep.core.SecretValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// The system-facing end: parse the screen, ask the coordinator, build the response. No vault, no
// secrets, nothing that outlives a request. Work runs off the binder thread, tied to this service.
class VazieAutofillService : AutofillService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val container get() = (application as KeepApplication).container

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val started = System.nanoTime()
        val job = scope.launch {
            val response = try {
                respond(request)
            } catch (_: RuntimeException) {
                null
            }
            container.autofillTiming("fill request", started)
            callback.onSuccess(response)
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private fun respond(request: FillRequest): android.service.autofill.FillResponse? {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return null
        val screen = AssistStructureAdapter.read(structure) ?: return null
        if (screen.packageName == packageName) return null
        val form = FormParser.parse(screen.nodes) ?: return null
        val plan = container.autofillCoordinator.plan(form, screen.packageName)
        if (plan !is FillPlan.Offer<AutofillId>) return null
        val inline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) request.inlineSuggestionsRequest else null
        return container.fillResponseFactory.build(plan, inline, offerSave = container.settings.value.autofillSaveOffers)
    }

    // Android calls this after the person tapped Save in the system prompt. The submitted password goes
    // to the save screen through an in-process hand-off, never an Intent; the screen asks again before
    // anything is written.
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val sender = try {
            prepareSave(request)
        } catch (_: RuntimeException) {
            null
        }
        if (sender != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) callback.onSuccess(sender) else callback.onSuccess()
    }

    private fun prepareSave(request: SaveRequest): IntentSender? {
        if (!container.settings.value.autofillSaveOffers) return null
        val structure = request.fillContexts.lastOrNull()?.structure ?: return null
        val screen = AssistStructureAdapter.read(structure, withValues = true) ?: return null
        if (screen.packageName == packageName) return null
        val form = FormParser.parseSubmitted(screen.nodes) ?: return null
        val origin = container.autofillCoordinator.originOf(form, screen.packageName) ?: return null
        if (!SaveEligibility.eligible(form, origin)) return null
        val values = screen.nodes.associate { it.id to it.value }
        val password = values[form.savedPassword]?.toString().orEmpty()
        if (password.isEmpty()) return null
        // A confirm field that disagrees means the form wasn't submitted as it looks.
        form.confirmPassword?.let { confirm -> values[confirm]?.toString()?.takeIf { it.isNotEmpty() && it != password }?.let { return null } }
        val username = form.username?.let { values[it]?.toString() }.orEmpty().trim()
        val secret = SecretValue(password.toByteArray())
        val token = container.saveHandoff.put(SubmittedCredential(origin, form.kind, username, secret))
        val intent = Intent(this, AutofillSaveActivity::class.java).putExtra(AutofillSaveActivity.TOKEN, token)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
