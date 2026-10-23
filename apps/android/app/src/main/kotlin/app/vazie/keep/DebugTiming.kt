package app.vazie.keep

import android.util.Log

// Debug builds only: durations in milliseconds, never labels derived from vault content.
internal object DebugTiming {
    fun log(label: String, millis: Long) {
        Log.d("VazieKeepPerf", "$label: $millis ms")
    }
}
