package app.vazie.keep.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import app.vazie.keep.MainActivity
import app.vazie.keep.R

// Builds what the framework shows. Every dataset is "authentication needed": it names an entry
// (title, username) and carries no value. The password is fetched only after the person taps it.
class FillResponseFactory(private val context: Context, private val launcherIcon: () -> Int) {
    fun build(plan: FillPlan.Offer<AutofillId>, inline: InlineSuggestionsRequest?, offerSave: Boolean = false): FillResponse? {
        val fingerprint = plan.fingerprint
        val form = plan.form
        val response = FillResponse.Builder()
        var content = false
        if (form.canFill) {
            addDatasets(response, plan, inline, fingerprint)
            content = true
        }
        if (offerSave && SaveEligibility.eligible(form, plan.origin)) {
            response.setSaveInfo(saveInfo(form, plan.origin))
            content = true
        }
        return if (content) response.build() else null
    }

    private fun saveInfo(form: ParsedForm<AutofillId>, origin: RequestOrigin): SaveInfo {
        val required = listOfNotNull(form.savedPassword, form.username)
        val type = SaveInfo.SAVE_DATA_TYPE_PASSWORD or if (form.username != null) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0
        return SaveInfo.Builder(type, required.toTypedArray()).apply {
            form.confirmPassword?.let { setOptionalIds(arrayOf(it)) }
            // A native login screen going away is how the system learns it was submitted.
            if (origin is RequestOrigin.App) setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
        }.build()
    }

    private fun addDatasets(response: FillResponse.Builder, plan: FillPlan.Offer<AutofillId>, inline: InlineSuggestionsRequest?, fingerprint: String?) {
        val form = plan.form
        val ids = listOfNotNull(form.username, form.password)
        val limit = inlineLimit(inline) ?: MAX_DATASETS
        val shown = plan.matches.take(minOf(limit, MAX_DATASETS))
        shown.forEachIndexed { position, match ->
            val request = AuthRequest(AuthRequest.Mode.Select, match.entry.entryId, fingerprint.orEmpty(), plan.origin, form.username, form.password)
            val title = match.entry.title.ifBlank { context.getString(R.string.entry_untitled) }
            response.addDataset(dataset(position, request, title, match.entry.username, inline, ids))
        }
        if (plan.matches.isEmpty()) {
            val request = AuthRequest(AuthRequest.Mode.Search, null, fingerprint.orEmpty(), plan.origin, form.username, form.password)
            response.addDataset(dataset(0, request, context.getString(R.string.autofill_search_title), context.getString(R.string.autofill_search_subtitle), inline, ids))
        }
    }

    private fun inlineLimit(inline: InlineSuggestionsRequest?): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) inline?.maxSuggestionCount?.coerceAtLeast(1) else null

    @Suppress("DEPRECATION")
    private fun dataset(position: Int, request: AuthRequest, title: String, subtitle: String, inline: InlineSuggestionsRequest?, ids: List<AutofillId>): Dataset {
        val sender = PendingIntent.getActivity(
            context,
            position,
            request.toIntent(context),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender
        val presentation = presentation(title, subtitle)
        val builder = Dataset.Builder(presentation)
        ids.forEach { builder.setValue(it, null) }
        builder.setAuthentication(sender)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inline != null) {
            inlinePresentation(inline, position, title, subtitle)?.let(builder::setInlinePresentation)
        }
        return builder.build()
    }

    private fun presentation(title: String, subtitle: String): RemoteViews = RemoteViews(context.packageName, R.layout.autofill_dataset).apply {
        setTextViewText(R.id.autofill_title, title)
        setTextViewText(R.id.autofill_subtitle, subtitle)
        setViewVisibility(R.id.autofill_subtitle, if (subtitle.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
        setImageViewResource(R.id.autofill_icon, launcherIcon())
    }

    // getSlice is how the androidx.autofill samples hand the content to the framework.
    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun inlinePresentation(request: InlineSuggestionsRequest, position: Int, title: String, subtitle: String): InlinePresentation? {
        val specs = request.inlinePresentationSpecs
        if (specs.isEmpty()) return null
        val spec = specs[minOf(position, specs.size - 1)]
        if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
        val attribution = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val content = InlineSuggestionUi.newContentBuilder(attribution)
            .setTitle(title)
            .apply { if (subtitle.isNotEmpty()) setSubtitle(subtitle) }
            .setStartIcon(Icon.createWithResource(context, launcherIcon()))
            .build()
        return InlinePresentation(content.slice, spec, false)
    }

    private companion object {
        const val MAX_DATASETS = 20
    }
}

object SaveEligibility {
    // Only a real credential form from an origin that can be believed: not a form with no password
    // (OTP, phone, search, card), and not an app that merely claims to be a web page.
    fun eligible(form: ParsedForm<*>, origin: RequestOrigin): Boolean {
        if (form.savedPassword == null) return false
        return when (origin) {
            is RequestOrigin.Web -> true
            is RequestOrigin.App -> !origin.ignoredWebClaim
        }
    }
}
