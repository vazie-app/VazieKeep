package app.vazie.keep.core

import java.io.Closeable

enum class ImportSource { Csv, OnePasswordCsv, OnePassword1pux, BitwardenJson, BitwardenCsv }

// What an export holds, without any of its secrets.
data class ImportSummary(val source: ImportSource, val entries: Int, val folders: Int, val withTotp: Int, val skipped: Int)

data class ImportOutcome(val entries: Int, val groups: Int)

// A parsed export. Its passwords stay in the core until a session takes them in; closing wipes them.
interface ImportPlan : Closeable {
    fun summary(): ImportSummary
}

class ImportException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { Empty, TooLarge, Unrecognized, Encrypted, Malformed }
}
