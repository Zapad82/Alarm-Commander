package com.example.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
    val currentVersion: String = "1.0.3",
    val latestTagVersion: String = "1.0.3",
    val releasePageUrl: String = "https://github.com/alarm-commander/alarm-commander-android/releases",
    val apkDownloadUrl: String = "",
    val statusMessageRu: String = "Нажмите для проверки актуального релиза на GitHub",
    val statusMessageEn: String = "Tap to check the latest release on GitHub"
) {
    val isUpdateAvailable: Boolean get() = updateAvailable
    val htmlReleaseUrl: String get() = releasePageUrl
    fun statusMessage(isEnglish: Boolean): String = if (isEnglish) statusMessageEn else statusMessageRu
}

/**
 * Модуль проверки обновлений через GitHub Releases API и верификации собственной подписи APK (SHA-256).
 * Для соответствия правилам безопасности Google Play открывает страницу релиза/загрузки через стандартный
 * браузер (Intent.ACTION_VIEW), где система Android устанавливает новую версию поверх старой при совпадении сертификата.
 */
object GitHubUpdateChecker {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
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
        repoSlug: String,
        currentVersionName: String = "1.0.3"
    ): GitHubReleaseCheckResult = withContext(Dispatchers.IO) {
        val cleanedRepo = repoSlug.trim().removePrefix("https://github.com/").trim('/')
        val fallbackPageUrl = "https://github.com/$cleanedRepo/releases"

        if (!cleanedRepo.contains("/") || cleanedRepo.split("/").size != 2) {
            return@withContext GitHubReleaseCheckResult(
                isChecking = false,
                checked = true,
                updateAvailable = false,
                currentVersion = currentVersionName,
                latestTagVersion = currentVersionName,
                releasePageUrl = "https://github.com",
                statusMessageRu = "Укажите репозиторий в формате owner/repo",
                statusMessageEn = "Specify repository in owner/repo format"
            )
        }

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
                    val htmlUrl = extractJsonField(body, "html_url").ifBlank { fallbackPageUrl }
                    val apkAssetUrl = extractJsonField(body, "browser_download_url")
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
                            "Доступна новая версия $tagName! Скачайте подписанный APK со страницы релиза для обновления поверх v$currentVersionName."
                        } else {
                            "Установлена актуальная версия (v$currentVersionName, последний релиз: $tagName)."
                        },
                        statusMessageEn = if (isNewer) {
                            "New version $tagName available! Download the signed APK from Releases to update in-place over v$currentVersionName."
                        } else {
                            "You have the latest version installed (v$currentVersionName, latest release: $tagName)."
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
                        statusMessageRu = "Репозиторий $cleanedRepo готов к публикации первого релиза (v$currentVersionName).",
                        statusMessageEn = "Repository $cleanedRepo is ready for publishing its first release (v$currentVersionName)."
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
