package app.futbolio.application.api

data class HostConfig(
    val port: Int = 7000, val bindHost: String = "127.0.0.1", val advertisedHost: String = "127.0.0.1",
    val accessToken: String = "", val desktop: Boolean = false,
) {
    init {
        require(port in 1024..65535)
        require(desktop || (bindHost == "127.0.0.1" && advertisedHost == "127.0.0.1" && accessToken.isEmpty())) { "Android must be same-device only" }
        require(advertisedHost.matches(Regex("[a-zA-Z0-9.-]+"))) { "Invalid advertised host" }
        require(accessToken.isEmpty() || accessToken.matches(Regex("[a-f0-9]{48}"))) { "Invalid access token" }
        require(bindHost == "127.0.0.1" || (desktop && accessToken.isNotEmpty())) { "LAN hosting requires an access token" }
    }
    val prefix get() = if (accessToken.isEmpty()) "" else "/a/$accessToken"
    val base get() = "http://$advertisedHost:$port$prefix"
    val manifest get() = "$base/manifest.json"
    override fun toString() = "HostConfig(port=$port, desktop=$desktop, accessToken=[redacted])"
}
