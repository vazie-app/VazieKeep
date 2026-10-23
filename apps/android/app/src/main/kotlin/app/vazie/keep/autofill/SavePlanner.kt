package app.vazie.keep.autofill

sealed interface SaveChoice {
    data object Create : SaveChoice

    data class Update(val entry: IndexedEntry) : SaveChoice
}

// What to offer for a submitted credential, from the non-secret index only.
sealed interface SavePlan {
    // Nothing to update (or a different username): a new entry, with the other entries for the same
    // site or app offered as an alternative.
    data class Create(val alternatives: List<IndexedEntry>) : SavePlan

    // Exactly one entry for this site or app and this username (or no username in the form).
    data class Update(val entry: IndexedEntry) : SavePlan

    // Several entries fit: the person picks, nothing is guessed.
    data class Choose(val candidates: List<IndexedEntry>) : SavePlan
}

object SavePlanner {
    fun plan(index: AutofillIndex?, origin: RequestOrigin, username: String, matcher: CredentialMatcher, associations: List<AppAssociation>): SavePlan {
        val forOrigin = index?.let { matcher.match(it, origin, associations).map(CredentialMatch::entry) }.orEmpty()
        val name = username.trim()
        if (name.isEmpty()) {
            // A password-change form often has no username field: the only entry for the site is the one.
            return when (forOrigin.size) {
                0 -> SavePlan.Create(emptyList())
                1 -> SavePlan.Update(forOrigin.single())
                else -> SavePlan.Choose(forOrigin)
            }
        }
        val same = forOrigin.filter { sameUsername(it.username, name) }
        return when (same.size) {
            0 -> SavePlan.Create(forOrigin)
            1 -> SavePlan.Update(same.single())
            else -> SavePlan.Choose(same)
        }
    }

    // Trimmed and exact. Addresses with an "@" also ignore case, because mail providers treat them so;
    // nothing else is merged heuristically.
    fun sameUsername(saved: String, submitted: String): Boolean {
        val a = saved.trim()
        val b = submitted.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || ('@' in a && '@' in b && a.equals(b, ignoreCase = true))
    }

    // The origin a new entry is saved for. A page keeps its scheme (https unless the browser said
    // http, never downgraded) and host, no path or query; the port isn't known to Android, so none is added.
    fun urlFor(origin: RequestOrigin): String = when (origin) {
        is RequestOrigin.Web -> "${origin.origin.scheme}://${origin.origin.host}"
        is RequestOrigin.App -> ""
    }

    fun titleFor(origin: RequestOrigin, appLabel: String?): String = when (origin) {
        is RequestOrigin.Web -> origin.origin.host.removePrefix("www.")
        is RequestOrigin.App -> appLabel?.takeIf { it.isNotBlank() } ?: origin.packageName
    }
}
