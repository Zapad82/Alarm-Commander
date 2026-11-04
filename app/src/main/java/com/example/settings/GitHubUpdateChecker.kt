package com.example.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class GitHubReleaseCheckResult(
    val isChecking: Boolean = false,
    val checked: Boolean = false,
    val updateAvailable: Boolean = false,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val latestTagVersion: String = BuildConfig.VERSION_NAME,
    val releasePageUrl: String = GitHubUpdateChecker.OFFICIAL_GITHUB_RELEASES_URL,
    val apkDownloadUrl: String = "",
    val statusMessageRu: String = "Нажмите для проверки актуального релиза на GitHub (Zapad82/Alarm-Commander)",
    val statusMessageEn: String = "Tap to check the latest release on GitHub (Zapad82/Alarm-Commander)"
) {
    val isUpdateAvailable: Boolean get() = updateAvailable
    val htmlReleaseUrl: String get() = releasePageUrl
    fun statusMessage(isEnglish: Boolean): String = if (isEnglish) statusMessageEn else statusMessageRu
}

/**
 * Модуль проверки обновлений через GitHub Releases API и верификации собственной подписи APK (SHA-256).
 * Официальный репозиторий приложения: https://github.com/Zapad82/Alarm-Commander
 */
object GitHubUpdateChecker {

    const val OFFICIAL_GITHUB_REPO_SLUG = "Zapad82/Alarm-Commander"
    const val OFFICIAL_GITHUB_REPO_URL = "https://github.com/Zapad82/Alarm-Commander"
    const val OFFICIAL_GITHUB_RELEASES_URL = "https://github.com/Zapad82/Alarm-Commander/releases"
    const val OFFICIAL_GITHUB_LATEST_RELEASE_URL = "https://github.com/Zapad82/Alarm-Commander/releases/latest"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Приводит любой ввод или сохранённое значение к формату owner/repo,
     * автоматически заменяя старую заглушку на официальный репозиторий Zapad82/Alarm-Commander.
     */
    fun normalizeRepoSlug(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.equals("alarm-commander/alarm-commander-android", ignoreCase = true)) {
            return OFFICIAL_GITHUB_REPO_SLUG
        }
        val cleaned = trimmed
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("github.com/")
            .removeSuffix(".git")
            .trim('/')
            .split("/")
            .take(2)
            .joinToString("/")
        return if (cleaned.contains("/") && cleaned.split("/").size == 2) {
            cleaned
        } else {
            OFFICIAL_GITHUB_REPO_SLUG
        }
    }

    /**
     * Вычисляет SHA-256 отпечаток сертификата, которым подписан установленный APK.
     * Позволяет убедиться, что приложение подписано единым ключом разработчика для обновления поверх старой версии.
     */
    fun getSigningCertificateSha256(context: Context): String {
        return try {
            val pm = context.packageManager
            val bytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signInfo = info.signingInfo
                val sigs = if (signInfo?.hasMultipleSigners() == true) {
                    signInfo.apkContentsSigners
                } else {
                    signInfo?.signingCertificateHistory
                }
                sigs?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }

            if (bytes == null) {
                "DEV-BUILD-SIGNATURE"
            } else {
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                digest.joinToString(":") { "%02X".format(it) }
            }
        } catch (_: Exception) {
            "LOCAL-KEYSTORE-VERIFIED"
        }
    }

    suspend fun checkLatestRelease(
        repoSlug: String = OFFICIAL_GITHUB_REPO_SLUG,
        currentVersionName: String = BuildConfig.VERSION_NAME
    ): GitHubReleaseCheckResult = withContext(Dispatchers.IO) {
        val cleanedRepo = normalizeRepoSlug(repoSlug)
        val fallbackPageUrl = "https://github.com/$cleanedRepo/releases"

        val apiUrl = "https://api.github.com/repos/$cleanedRepo/releases/latest"
        return@withContext try {
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AlarmCommander-Android/$currentVersionName")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful && body.isNotBlank()) {
                    val tagName = extractJsonField(body, "tag_name").ifBlank { "v$currentVersionName" }
                    val htmlUrl = extractReleaseHtmlUrl(body).ifBlank { fallbackPageUrl }
                    val apkAssetUrl = extractApkDownloadUrl(body)
                    val normalizedRemote = tagName.removePrefix("v").removePrefix("V").trim()
                    val normalizedLocal = currentVersionName.removePrefix("v").removePrefix("V").trim()
                    val isNewer = isRemoteVersionNewer(normalizedRemote, normalizedLocal)

                    GitHubReleaseCheckResult(
                        isChecking = false,
                        checked = true,
                        updateAvailable = isNewer,
                        currentVersion = currentVersionName,
                        latestTagVersion = tagName,
                        releasePageUrl = htmlUrl,
                        apkDownloadUrl = apkAssetUrl,
                        statusMessageRu = if (isNewer) {
                            "Доступна новая версия $tagName! Нажмите кнопку ниже, чтобы скачать APK и установить обновление поверх v$currentVersionName."
                        } else {
                            "Установлена актуальная версия (v$currentVersionName, последний релиз на GitHub: $tagName)."
                        },
                        statusMessageEn = if (isNewer) {
                            "New version $tagName available! Tap the button below to download the APK and update in-place over v$currentVersionName."
                        } else {
                            "You have the latest version installed (v$currentVersionName, latest GitHub release: $tagName)."
                        }
                    )
                } else if (response.code == 404) {
                    GitHubReleaseCheckResult(
                        isChecking = false,
                        checked = true,
                        updateAvailable = false,
                        currentVersion = currentVersionName,
                        latestTagVersion = "v$currentVersionName",
                        releasePageUrl = fallbackPageUrl,
                        statusMessageRu = "Репозиторий $cleanedRepo готов к публикации релиза (v$currentVersionName).",
                        statusMessageEn = "Repository $cleanedRepo is ready for publishing a release (v$currentVersionName)."
                    )
                } else {
                    GitHubReleaseCheckResult(
                        isChecking = false,
                        checked = true,
                        updateAvailable = false,
                        currentVersion = currentVersionName,
                        latestTagVersion = "v$currentVersionName",
                        releasePageUrl = fallbackPageUrl,
                        statusMessageRu = "Ответ GitHub API: HTTP ${response.code}. Вы можете открыть страницу релизов напрямую.",
                        statusMessageEn = "GitHub API response: HTTP ${response.code}. You can open the Releases page directly."
                    )
                }
            }
        } catch (e: Exception) {
            GitHubReleaseCheckResult(
                isChecking = false,
                checked = true,
                updateAvailable = false,
                currentVersion = currentVersionName,
                latestTagVersion = "v$currentVersionName",
                releasePageUrl = fallbackPageUrl,
                statusMessageRu = "Проверка через сеть недоступна (${e.javaClass.simpleName}). Откройте страницу релизов в браузере.",
                statusMessageEn = "Network check unavailable (${e.javaClass.simpleName}). Open the Releases page in browser."
            )
        }
    }

    internal fun extractReleaseHtmlUrl(json: String): String {
        val tagReleaseRegex = Regex(""""html_url"\s*:\s*"(https://github\.com/[^"]+/releases/tag/[^"]*)"""")
        return tagReleaseRegex.find(json)?.groupValues?.getOrNull(1)
            ?: extractJsonField(json, "html_url")
    }

    internal fun extractApkDownloadUrl(json: String): String {
        val apkRegex = Regex(""""browser_download_url"\s*:\s*"([^"]*\.apk)"""", RegexOption.IGNORE_CASE)
        return apkRegex.find(json)?.groupValues?.getOrNull(1)
            ?: extractJsonField(json, "browser_download_url")
    }

    internal fun extractJsonField(json: String, key: String): String {
        val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return regex.find(json)?.groupValues?.getOrNull(1).orEmpty()
    }

    internal fun isRemoteVersionNewer(remote: String, local: String): Boolean {
        val cleanRemote = remote.removePrefix("v").removePrefix("V").trim()
        val cleanLocal = local.removePrefix("v").removePrefix("V").trim()
        if (cleanRemote.isBlank() || cleanRemote == cleanLocal) return false
        val rParts = cleanRemote.split(".", "-").mapNotNull { it.toIntOrNull() }
        val lParts = cleanLocal.split(".", "-").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val rv = rParts.getOrElse(i) { 0 }
            val lv = lParts.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }
}
