package com.resqmesh.app.settings

import com.resqmesh.app.BuildConfig

/**
 * Which numbers this build may text (TRD 2.7 number policy). During the project SOS go only to the
 * team's own test phones, never to 112 or any other emergency line: the allow-list comes from
 * `resqmesh.smsNumbers` in local.properties (kept out of git), and a build without one sends no SMS.
 */
object SmsPolicy {
    /** Short codes and emergency lines, blocked even if someone puts them on the allow-list. */
    private val BLOCKED = setOf("100", "101", "102", "108", "112", "911", "999", "1091", "1098", "1070", "1078")

    val allowed: List<String> = BuildConfig.SMS_ALLOWED_NUMBERS.split(',').map(::normalize).filter(::isPhoneNumber)

    /** Digits only, with a leading + kept: "+91 98450-00000" becomes "+919845000000". */
    fun normalize(number: String): String {
        val trimmed = number.trim()
        val digits = trimmed.filter(Char::isDigit)
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    fun isAllowed(number: String): Boolean {
        val clean = normalize(number)
        return isPhoneNumber(clean) && allowed.any { sameNumber(it, clean) }
    }

    /** At least 10 digits: rules out every short code and emergency number. */
    private fun isPhoneNumber(number: String): Boolean =
        number.count(Char::isDigit) >= 10 && number.trimStart('+') !in BLOCKED

    /** "+919845000000" and "9845000000" are the same Indian number. */
    private fun sameNumber(a: String, b: String): Boolean = a.filter(Char::isDigit).takeLast(10) == b.filter(Char::isDigit).takeLast(10)
}
