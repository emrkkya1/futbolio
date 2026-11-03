package app.futbolio.domain.identity

import java.security.MessageDigest

/** Existing 96-bit public ID digest. Changing this requires an identity migration. */
object StableIdentity {
    private const val HEX = "0123456789abcdef"
    fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        // Same first 96 bits, without 32 Formatter allocations for every listing/event.
        return buildString(24) {
            for (i in 0 until 12) {
                val byte = digest[i].toInt() and 0xff
                append(HEX[byte ushr 4]); append(HEX[byte and 15])
            }
        }
    }
}
