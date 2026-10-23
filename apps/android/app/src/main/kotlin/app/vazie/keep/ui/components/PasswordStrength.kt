package app.vazie.keep.ui.components

import androidx.annotation.StringRes
import app.vazie.keep.R
import kotlin.math.ln
import kotlin.math.roundToInt

class StrengthEstimate(val level: Int, val bits: Int) {
    @get:StringRes
    val label: Int
        get() = when (level) {
            0 -> R.string.strength_fragile
            1 -> R.string.strength_fair
            2 -> R.string.strength_sturdy
            3 -> R.string.strength_strong
            else -> R.string.strength_formidable
        }
}

object PasswordStrength {
    private val LOG2 = ln(2.0)

    fun estimate(password: CharSequence): StrengthEstimate? {
        if (password.isEmpty()) return null
        var lower = false
        var upper = false
        var digit = false
        var symbol = false
        var other = false
        for (index in password.indices) {
            val ch = password[index]
            when {
                ch in 'a'..'z' -> lower = true
                ch in 'A'..'Z' -> upper = true
                ch in '0'..'9' -> digit = true
                ch.code < 128 -> symbol = true
                else -> other = true
            }
        }
        val pool = (if (lower) 26 else 0) + (if (upper) 26 else 0) + (if (digit) 10 else 0) + (if (symbol) 33 else 0) + (if (other) 64 else 0)
        val bits = password.length * ln(pool.coerceAtLeast(2).toDouble()) / LOG2
        val level = when {
            bits < 40 -> 0
            bits < 60 -> 1
            bits < 80 -> 2
            bits < 100 -> 3
            else -> 4
        }
        return StrengthEstimate(level, bits.roundToInt())
    }
}
