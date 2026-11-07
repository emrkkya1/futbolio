package app.futbolio.application.ports

import app.futbolio.domain.provider.SourceUrl

data class SourceHeaders(
    val referer: String = "", val origin: String = "", val extra: Map<String, String> = emptyMap(),
    val authOrigin: String = "", val authToken: String = "",
) {
    fun scopedAuthFor(url: String) = authToken.takeIf { it.isNotEmpty() && SourceUrl.origin(url) == authOrigin }

    init {
        require((authOrigin.isEmpty() && authToken.isEmpty()) ||
            (authToken.isNotEmpty() && authToken.length <= 4096 && authToken.none { it == '\r' || it == '\n' } &&
                authOrigin.startsWith("https://") && SourceUrl.origin(authOrigin) == authOrigin)) { "Invalid scoped authentication" }
        require(!referer.contains(Regex("[\\r\\n]")) && !origin.contains(Regex("[\\r\\n]"))) { "Invalid source headers" }
        require(extra.keys.all { it.lowercase() in listOf("usertoken", "pl") } &&
            extra.values.all { it.length <= 4096 && !it.contains(Regex("[\\r\\n]")) }) { "Unsupported source headers" }
    }

    override fun toString() = "SourceHeaders([redacted])"
}

interface PlaybackSession {
    val id: String
    suspend fun open(url: String, range: String? = null): UpstreamResponse
}

data class StreamChoice(
    val title: String, val url: String = "", val headers: SourceHeaders = SourceHeaders(),
    val externalUrl: String = "", val providers: List<String> = emptyList(), val mediaType: String = "",
    val session: PlaybackSession? = null,
    val assessment: StreamAssessment = StreamAssessment(),
) {
    override fun toString() = "StreamChoice(inline=${url.isNotEmpty()}, external=${externalUrl.isNotEmpty()}, credentials=[redacted])"
}

fun isHls(url: String) = Regex("\\.m3u8(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)
fun isHls(choice: StreamChoice) = choice.mediaType == "hls" || isHls(choice.url)
