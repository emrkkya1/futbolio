package app.futbolio.android.updates

import kotlinx.serialization.json.*
import kotlin.test.*

class ReleasePolicyTest {
    private val hash = "a".repeat(64)
    private fun fixture(
        tag: String = "v0.3.4", draft: Boolean = false, prerelease: Boolean = false,
        name: String = "futbolio-$tag.apk", url: String = "https://github.com/emrkkya1/futbolio/releases/download/$tag/$name",
        size: Long = 1024, digest: String? = "sha256:$hash", state: String = "uploaded", duplicate: Boolean = false,
    ): String = buildJsonObject {
        put("tag_name", tag); put("draft", draft); put("prerelease", prerelease)
        putJsonArray("assets") {
            val asset = buildJsonObject {
                put("name", name); put("browser_download_url", url); put("size", size); put("state", state)
                if (digest != null) put("digest", digest)
            }
            add(asset)
            if (duplicate) add(asset)
        }
    }.toString()

    @Test fun selectsExactStableApk() {
        val release = assertNotNull(ReleasePolicy.select(fixture(), "0.3.3"))
        assertEquals("v0.3.4", release.tag)
        assertEquals(3004L, release.version.code)
        assertEquals(1024L, release.bytes)
        assertEquals(hash, release.sha256)
    }

    @Test fun comparesVersionsNumerically() {
        assertNotNull(ReleasePolicy.select(fixture(tag = "v0.3.10"), "0.3.9"))
        assertNotNull(ReleasePolicy.select(fixture(tag = "v1.0.0"), "0.999.999"))
        assertNull(ReleasePolicy.select(fixture(tag = "v0.3.9"), "0.3.10"))
    }

    @Test fun neverOffersEqualOrOlderRelease() {
        assertNull(ReleasePolicy.select(fixture(), "0.3.4"))
        assertNull(ReleasePolicy.select(fixture(), "0.4.0"))
    }

    @Test fun excludesDraftsAndPrereleases() {
        assertNull(ReleasePolicy.select(fixture(draft = true), "0.3.3"))
        assertNull(ReleasePolicy.select(fixture(prerelease = true), "0.3.3"))
        assertNull(ReleasePolicy.select(fixture(tag = "v0.3.4-beta.1"), "0.3.3"))
    }

    @Test fun versionBoundsMatchAndroidBuild() {
        assertEquals(2099999999L, ReleaseVersion.parse("v2099.999.999")?.code)
        listOf("0.0.0", "2100.0.0", "0.1000.0", "0.0.1000", "01.2.3", "1.02.3", "1.2.03", "1.2", "1.2.3+build", "999999999999999999.0.0").forEach {
            assertNull(ReleaseVersion.parse(it), it)
        }
        assertNull(ReleasePolicy.select(fixture(), "unknown"))
    }

    @Test fun rejectsWrongFileAndAmbiguousAssets() {
        assertNull(ReleasePolicy.select(fixture(name = "futbolio-debug.apk"), "0.3.3"))
        assertNull(ReleasePolicy.select(fixture(state = "open"), "0.3.3"))
        assertNull(ReleasePolicy.select(fixture(duplicate = true), "0.3.3"))
        assertNull(ReleasePolicy.select("""{"tag_name":"v0.3.4","draft":false,"prerelease":false,"assets":[]}""", "0.3.3"))
    }

    @Test fun rejectsUntrustedDownloadAddresses() {
        listOf(
            "http://github.com/emrkkya1/futbolio/releases/download/v0.3.4/futbolio-v0.3.4.apk",
            "https://github.com.evil.example/emrkkya1/futbolio/releases/download/v0.3.4/futbolio-v0.3.4.apk",
            "https://github.com/another/repository/releases/download/v0.3.4/futbolio-v0.3.4.apk",
            "https://evil.example/update.apk", "file:///data/local/tmp/update.apk",
        ).forEach { assertNull(ReleasePolicy.select(fixture(url = it), "0.3.3"), it) }
    }

    @Test fun requiresSaneSizeAndGitHubSha256() {
        listOf(0L, -1L, ReleasePolicy.MAX_APK_BYTES + 1).forEach {
            assertNull(ReleasePolicy.select(fixture(size = it), "0.3.3"))
        }
        listOf(null, "", "md5:$hash", "sha256:abc", "sha256:${"z".repeat(64)}").forEach {
            assertNull(ReleasePolicy.select(fixture(digest = it), "0.3.3"))
        }
        assertEquals(hash, ReleasePolicy.select(fixture(digest = "sha256:${hash.uppercase()}"), "0.3.3")?.sha256)
    }

    @Test fun rejectsTruncatedOrTamperedDownloads() {
        val release = assertNotNull(ReleasePolicy.select(fixture(), "0.3.3"))
        ReleasePolicy.verifyDownload(release, 1024, hash)
        assertFailsWith<IllegalArgumentException> { ReleasePolicy.verifyDownload(release, 1023, hash) }
        assertFailsWith<IllegalArgumentException> { ReleasePolicy.verifyDownload(release, 1025, hash) }
        assertFailsWith<IllegalArgumentException> { ReleasePolicy.verifyDownload(release, 1024, "b".repeat(64)) }
    }
}
