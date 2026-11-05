package app.futbolio.android.updates

import kotlinx.serialization.json.*

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    val code: Long get() = major * 1_000_000L + minor * 1_000L + patch
    override fun compareTo(other: ReleaseVersion) = code.compareTo(other.code)

    companion object {
        fun parse(value: String): ReleaseVersion? {
            val match = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(value) ?: return null
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            if (parts[0] !in 0..2099 || parts[1] !in 0..999 || parts[2] !in 0..999) return null
            return ReleaseVersion(parts[0], parts[1], parts[2]).takeIf { it.code > 0 }
        }
    }
}

internal data class GitHubRelease(val tag: String, val version: ReleaseVersion, val url: String, val bytes: Long, val sha256: String)

internal object ReleasePolicy {
    const val ENDPOINT = "https://api.github.com/repos/emrkkya1/futbolio/releases/latest"
    const val MAX_APK_BYTES = 200L * 1024 * 1024

    /** Only stable releases and the exact APK filename published by this repository's CI. */
    fun select(json: String, installedVersion: String): GitHubRelease? {
        val current = ReleaseVersion.parse(installedVersion) ?: return null
        val release = Json.parseToJsonElement(json).jsonObject
        if (release["draft"]?.jsonPrimitive?.booleanOrNull != false || release["prerelease"]?.jsonPrimitive?.booleanOrNull != false) return null
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val version = ReleaseVersion.parse(tag) ?: return null
        if (version <= current) return null
        val name = "futbolio-$tag.apk"
        val asset = release["assets"]?.jsonArray?.map { it.jsonObject }?.singleOrNull {
            it["name"]?.jsonPrimitive?.contentOrNull == name && it["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
        } ?: return null
        val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
        if (url != "https://github.com/emrkkya1/futbolio/releases/download/$tag/$name") return null
        val bytes = asset["size"]?.jsonPrimitive?.longOrNull ?: return null
        if (bytes !in 1..MAX_APK_BYTES) return null
        val digest = asset["digest"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!digest.matches(Regex("sha256:[a-fA-F0-9]{64}"))) return null
        return GitHubRelease(tag, version, url, bytes, digest.substringAfter(':').lowercase())
    }

    fun verifyDownload(release: GitHubRelease, bytes: Long, sha256: String) {
        require(bytes == release.bytes) { "The update download was incomplete. Please retry." }
        require(sha256 == release.sha256) { "The update checksum did not match GitHub. Please retry later." }
    }
}
