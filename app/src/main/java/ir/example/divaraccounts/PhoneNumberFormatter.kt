package ir.example.divaraccounts

/** Formats Iranian mobile numbers for display without changing the stored/login value. */
object PhoneNumberFormatter {
    fun formatIranian(raw: String): String {
        val digits = buildString {
            raw.forEach { char ->
                val digit = Character.digit(char, 10)
                if (digit in 0..9) append(('0'.code + digit).toChar())
            }
        }

        val local = when {
            digits.startsWith("0098") && digits.length == 14 -> "0" + digits.drop(4)
            digits.startsWith("98") && digits.length == 12 -> "0" + digits.drop(2)
            digits.startsWith("9") && digits.length == 10 -> "0$digits"
            else -> digits
        }

        return if (local.length == 11 && local.startsWith("0")) {
            "${local.substring(0, 4)} ${local.substring(4, 7)} ${local.substring(7)}"
        } else {
            raw.trim()
        }
    }
}
