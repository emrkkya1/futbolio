package app.futbolio.engine

import kotlinx.serialization.json.Json
import java.security.SecureRandom

internal val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

internal fun hash(value: String) = app.futbolio.domain.identity.StableIdentity.hash(value)
internal fun opaqueToken(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
