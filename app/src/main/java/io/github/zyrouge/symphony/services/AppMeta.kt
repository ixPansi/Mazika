package io.github.zyrouge.symphony.services

import io.github.zyrouge.symphony.BuildConfig
import io.github.zyrouge.symphony.utils.HttpClient
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.CacheControl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

@Suppress("ConstPropertyName")
object AppMeta {
    const val appName = "MAZIKA"
    const val version = "v${BuildConfig.VERSION_NAME}"

    const val sourceCodeUrl = "https://github.com/ixPansi/Mazika"
    const val upstreamRepositoryUrl = "https://github.com/zyrouge/symphony"
    const val licenseUrl = "https://www.gnu.org/licenses/agpl-3.0.html"

    data class Release(val tag: String, val htmlUrl: String)

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data object UpToDate : UpdateState
        data object Failed : UpdateState
        data class Available(val release: Release) : UpdateState
    }

    private data class StableVersion(val year: Long, val month: Long, val code: Long)

    private val stableVersionPattern = Regex("^v?(\\d{4})\\.(\\d{1,2})\\.(\\d+)$")
    private val githubRepositoryPattern = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    val githubRepository = BuildConfig.MAZIKA_GITHUB_REPOSITORY
        .trim()
        .takeIf { githubRepositoryPattern.matches(it) }
        .orEmpty()
    val canCheckForUpdates = githubRepository.isNotEmpty() && !isCanaryBuild()

    private val mutableUpdateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState = mutableUpdateState.asStateFlow()

    fun isNightlyBuild() = version.contains("-nightly")
    private fun isCanaryBuild() = BuildConfig.BUILD_TYPE == "canary" || version.contains("-canary")

    fun fetchLatestVersion(): Release? {
        if (!canCheckForUpdates) {
            mutableUpdateState.value = UpdateState.Idle
            return null
        }

        mutableUpdateState.value = UpdateState.Checking
        return try {
            val latestRelease = when {
                isNightlyBuild() -> fetchLatestNightlyRelease()
                else -> fetchLatestStableRelease()
            }
            val availableRelease = latestRelease?.takeIf { release ->
                when {
                    isNightlyBuild() -> release.tag != version
                    else -> isNewerStableVersion(version, release.tag)
                }
            }
            mutableUpdateState.value = availableRelease
                ?.let { UpdateState.Available(it) }
                ?: UpdateState.UpToDate
            availableRelease
        } catch (err: Exception) {
            Logger.warn("AppMeta", "version check failed: $err")
            mutableUpdateState.value = UpdateState.Failed
            null
        }
    }

    internal fun isNewerStableVersion(current: String, candidate: String): Boolean {
        val currentVersion = parseStableVersion(current) ?: return false
        val candidateVersion = parseStableVersion(candidate) ?: return false
        return when {
            candidateVersion.year != currentVersion.year ->
                candidateVersion.year > currentVersion.year
            candidateVersion.month != currentVersion.month ->
                candidateVersion.month > currentVersion.month
            else -> candidateVersion.code > currentVersion.code
        }
    }

    private fun parseStableVersion(value: String): StableVersion? {
        val match = stableVersionPattern.matchEntire(value) ?: return null
        val year = match.groupValues[1].toLongOrNull() ?: return null
        val month = match.groupValues[2].toLongOrNull()?.takeIf { it in 1..12 } ?: return null
        val code = match.groupValues[3].toLongOrNull() ?: return null
        return StableVersion(year, month, code)
    }

    private fun fetchLatestStableRelease(): Release? {
        val content = fetchGithubResponse(
            "https://api.github.com/repos/$githubRepository/releases/latest"
        )
        val release = JSONObject(content)
        if (release.getBoolean("draft") || release.getBoolean("prerelease")) {
            return null
        }
        return release.toRelease()
    }

    private fun fetchLatestNightlyRelease(): Release? {
        val content = fetchGithubResponse(
            "https://api.github.com/repos/$githubRepository/releases"
        )
        val releases = JSONArray(content)
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (
                !release.getBoolean("draft") &&
                release.getBoolean("prerelease") &&
                release.optString("tag_name").contains("-nightly")
            ) {
                return release.toRelease()
            }
        }
        return null
    }

    private fun JSONObject.toRelease(): Release? {
        val tag = getString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val htmlUrl = getString("html_url").takeIf { it.isNotBlank() } ?: return null
        return Release(tag = tag, htmlUrl = htmlUrl)
    }

    private fun fetchGithubResponse(url: String): String {
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        return HttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub release request failed with HTTP ${response.code}")
            }
            response.body?.string() ?: throw IOException("GitHub release response had no body")
        }
    }
}
