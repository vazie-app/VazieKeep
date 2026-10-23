package app.vazie.keep.core

enum class TotpAlgorithm { Sha1, Sha256, Sha512 }

class TotpCode(val code: SecretValue, val period: Int, val secondsRemaining: Int) : AutoCloseable {
    override fun close() = code.close()

    override fun toString() = "TotpCode(period=$period, secondsRemaining=$secondsRemaining)"
}

class TotpSetup(
    val uri: SecretValue,
    val algorithm: TotpAlgorithm,
    val digits: Int,
    val period: Int,
    val steam: Boolean,
    val issuer: String,
    val account: String,
) : AutoCloseable {
    override fun close() = uri.close()

    override fun toString() = "TotpSetup(digits=$digits, period=$period, steam=$steam)"
}

class TotpException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        SessionNotFound,
        SessionClosed,
        EntryNotFound,
        NotEntitled,
        NoCode,
        Empty,
        TooLong,
        UnsupportedType,
        MissingSecret,
        InvalidSecret,
        InvalidAlgorithm,
        InvalidDigits,
        InvalidPeriod,
        Migration,
        Malformed,
        InvalidText,
    }
}

const val OTP_FIELD_NAME = "otp"

private val TOTP_FIELD_NAMES = setOf(
    OTP_FIELD_NAME,
    "TimeOtp-Secret-Base32",
    "TimeOtp-Secret-Hex",
    "TimeOtp-Secret",
    "TimeOtp-Length",
    "TimeOtp-Period",
    "TimeOtp-Algorithm",
    "TOTP Seed",
    "TOTP Settings",
)

fun isTotpFieldName(name: String): Boolean = name in TOTP_FIELD_NAMES
