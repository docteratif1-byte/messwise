package com.kodawick.messwise.core

/** Pakistani rupee amounts held as whole paisa (Rs 1 = 100 paisa). Never floating point. */
object Money {
    /** "Rs 5,000", "Rs 1,234.50". */
    fun format(paisa: Long, withSymbol: Boolean = true): String {
        val negative = paisa < 0
        val abs = if (paisa == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(paisa)
        val rupees = abs / 100
        val rest = abs % 100
        val grouped = group(rupees)
        val body = if (rest == 0L) grouped else grouped + "." + rest.toString().padStart(2, '0')
        val sign = if (negative) "-" else ""
        return if (withSymbol) "${sign}Rs $body" else sign + body
    }

    /** Compact for small spaces: 2.5L for 250,000 rupees, 75K, 950. */
    fun compact(paisa: Long): String {
        val rupees = paisa / 100
        val abs = kotlin.math.abs(rupees)
        val sign = if (rupees < 0) "-" else ""
        return when {
            abs >= 10_000_000 -> sign + trim(abs / 10_000_000.0) + " Cr"
            abs >= 100_000 -> sign + trim(abs / 100_000.0) + " Lac"
            abs >= 1_000 -> sign + trim(abs / 1_000.0) + "K"
            else -> sign + abs.toString()
        }
    }

    /** Parses what a person types ("5,000", "5000.5", "Rs 1,200") into paisa, or null if it is not a valid amount. */
    fun parse(text: String): Long? {
        val cleaned = text.trim().removePrefix("Rs").removePrefix("rs").removePrefix("PKR").trim().replace(",", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val normal = toAsciiDigits(cleaned)
        if (!Regex("^\\d{1,12}(\\.\\d{0,2})?$").matches(normal)) return null
        val parts = normal.split(".")
        val rupees = parts[0].toLong()
        val paisa = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].padEnd(2, '0').toLong() else 0L
        return rupees * 100 + paisa
    }

    /** Text field value for an amount in paisa: "5000" or "5000.5". */
    fun toInput(paisa: Long): String {
        val r = paisa / 100
        val p = paisa % 100
        return if (p == 0L) r.toString() else "$r." + p.toString().padStart(2, '0').trimEnd('0')
    }

    /** Urdu and Arabic-Indic digits typed on an Urdu keyboard become 0-9. */
    fun toAsciiDigits(s: String): String = buildString {
        for (c in s) {
            append(
                when (c) {
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    else -> c
                }
            )
        }
    }

    private fun group(n: Long): String {
        val s = n.toString()
        val sb = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun trim(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }
}
