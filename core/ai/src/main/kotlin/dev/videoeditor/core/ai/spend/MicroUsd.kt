package dev.videoeditor.core.ai.spend

import java.math.BigDecimal
import java.math.RoundingMode

/** Integer micro-USD (1 USD = 1_000_000). No floating point anywhere in spend accounting. */
@JvmInline
value class MicroUsd(
    val value: Long,
) : Comparable<MicroUsd> {
    override fun compareTo(other: MicroUsd): Int = value.compareTo(other.value)

    operator fun plus(other: MicroUsd): MicroUsd = MicroUsd(Math.addExact(value, other.value))

    operator fun minus(other: MicroUsd): MicroUsd = MicroUsd(Math.subtractExact(value, other.value))

    /** "$0.18" style, rounded half-up to whole cents. For display only. */
    fun toUsdString(): String =
        "$" +
            BigDecimal
                .valueOf(value)
                .movePointLeft(6)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString()

    override fun toString(): String = "MicroUsd($value)"

    companion object {
        val ZERO = MicroUsd(0)
        const val PER_USD = 1_000_000L

        fun ofUsd(dollars: Long): MicroUsd = MicroUsd(Math.multiplyExact(dollars, PER_USD))

        fun ofCents(cents: Long): MicroUsd = MicroUsd(Math.multiplyExact(cents, PER_USD / 100))

        /** Exact decimal parse, e.g. "0.10". Throws if it is not a whole number of micro-USD. */
        fun parse(usd: String): MicroUsd = MicroUsd(BigDecimal(usd).movePointRight(6).longValueExact())
    }
}
