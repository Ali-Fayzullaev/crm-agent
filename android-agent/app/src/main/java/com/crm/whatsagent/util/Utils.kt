package com.crm.whatsagent.util

import java.security.MessageDigest

object IdempotencyKey {
    /**
     * Generates a stable SHA-256 fingerprint for deduplication.
     * Must match the backend's IdempotencyKey() function in service/message_service.go.
     */
    fun compute(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0) // separator byte
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

object PhoneNormalizer {
    /** Strips spaces and dashes, ensures E.164-ish format (leading +). */
    fun normalize(raw: String): String {
        val digits = digits(raw)
        return if (digits.startsWith("+")) digits
        else if (digits.length > 10) "+$digits"
        else digits
    }

    /** Returns only digit characters (and leading +). */
    fun digits(raw: String): String = raw.filter { it.isDigit() || it == '+' }
}
