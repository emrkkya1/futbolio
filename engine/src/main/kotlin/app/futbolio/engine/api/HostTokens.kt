package app.futbolio.engine.api

/** Cryptographically random desktop bearer-token creation; no persistence or rotation side effects. */
object HostTokens {
    fun create(): String = app.futbolio.engine.opaqueToken()
}
