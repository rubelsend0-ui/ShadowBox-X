package com.shadowbox.x.domain.tools

import java.security.SecureRandom
import kotlin.math.ln

object PasswordTools {
    private val rng = SecureRandom()
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.?"

    fun generate(length: Int, upper: Boolean, lower: Boolean, digits: Boolean, symbols: Boolean): String {
        val sets = buildList {
            if (upper) add(UPPER); if (lower) add(LOWER); if (digits) add(DIGITS); if (symbols) add(SYMBOLS)
        }.ifEmpty { listOf(LOWER) }
        val all = sets.joinToString("")
        val chars = MutableList(length) { all[rng.nextInt(all.length)] }
        // guarantee at least one char of each selected set
        sets.forEachIndexed { i, s -> if (i < length) chars[i] = s[rng.nextInt(s.length)] }
        chars.shuffle(rng)
        return chars.joinToString("")
    }

    data class Strength(val score: Int, val label: String)

    /** Rough entropy-based estimate, 0..4. Not a guarantee. */
    fun strength(pw: String): Strength {
        if (pw.isEmpty()) return Strength(0, "Empty")
        var pool = 0
        if (pw.any { it.isLowerCase() }) pool += 26
        if (pw.any { it.isUpperCase() }) pool += 26
        if (pw.any { it.isDigit() }) pool += 10
        if (pw.any { !it.isLetterOrDigit() }) pool += 32
        val unique = pw.toSet().size
        var bits = pw.length * ln(pool.coerceAtLeast(2).toDouble()) / ln(2.0)
        if (unique < pw.length / 2) bits *= 0.6
        val lowered = pw.lowercase()
        if (listOf("password", "qwerty", "123456", "letmein", "admin", "iloveyou").any { lowered.contains(it) }) bits *= 0.4
        return when {
            bits < 28 -> Strength(0, "Very weak")
            bits < 40 -> Strength(1, "Weak")
            bits < 60 -> Strength(2, "Fair")
            bits < 80 -> Strength(3, "Strong")
            else -> Strength(4, "Excellent")
        }
    }
}
