package com.xingheyuzhuan.shiguangschedule.tool

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.xingheyuzhuan.shiguangschedule.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** 更新渠道信息 */
data class UpdateChannel(val id: String, val title: String, val url: String)

/** 更新检查结果状态 */
sealed class UpdateStatus {
    data class Found(val flavorInfo: FlavorUpdateInfo, val downloadUrl: String, val checksum: String? = null) : UpdateStatus()
    data class Latest(val versionName: String) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
    object Checking : UpdateStatus()
    object Idle : UpdateStatus()
}

/** 远程更新索引的根结构 */
@Serializable data class UpdateIndex(val prod: FlavorUpdateInfo, val dev: FlavorUpdateInfo)
@Serializable
data class FlavorUpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val changelog: String = "",
    val downloadLinks: Map<String, String>,
    val checksums: Map<String, String> = emptyMap()
)


/**
 * 版本更新检测的远端 JSON 地址。
 *
 * 期望的 JSON 响应格式（kotlinx.serialization 自动解析，ignoreUnknownKeys = true）：
 * ```
 * {
 *   "prod": {
 *     "latestVersionCode": 31,          // 与 BuildConfig.VERSION_CODE 比较
 *     "latestVersionName": "1.2.3",
 *     "changelog": "更新内容说明",
 *     "downloadLinks": {               // key 为设备 ABI，value 为下载 URL
 *       "arm64-v8a": "https://...",
 *       "armeabi-v7a": "https://...",
 *       "x86_64": "https://...",
 *       "universal": "https://..."
 *     }
 *   },
 *   "dev": {                           // 与 prod 结构相同
 *     "latestVersionCode": ...,
 *     "latestVersionName": "...",
 *     "changelog": "...",
 *     "downloadLinks": { ... }
 *   }
 * }
 * ```
 * 请将 YOUR_UPDATE_URL_HERE 替换为实际服务器地址。
 */
const val UPDATE_REPO_URL = "https://gitee.com/TangeTiw/scnu-schedule/raw/v1.4.0/update.json"

class UpdateChecker(private val context: Context) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val httpClient = OkHttpClient.Builder().build()
    private val currentFlavorId = BuildConfig.CURRENT_FLAVOR_ID
    private val currentVersionCode = BuildConfig.VERSION_CODE

    /**
     * 在应用内下载 APK 到私有目录，返回结果文件。
     * 若提供了 expectedSha256，下载完成后会做 SHA-256 完整性校验。
     */
    suspend fun downloadApk(
        downloadUrl: String,
        expectedSha256: String?,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(downloadUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载失败 (HTTP ${response.code})")
                val body = response.body
                val contentLength = body.contentLength()

                val apkDir = File(context.filesDir, "apk").apply { mkdirs() }
                val tempFile = File(apkDir, "update.apk.tmp")

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            total += read
                            if (contentLength > 0) {
                                onProgress(((total * 100) / contentLength).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }

                if (!expectedSha256.isNullOrBlank()) {
                    val actual = tempFile.sha256()
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        tempFile.delete()
                        throw IOException("文件校验失败（SHA-256 不匹配）")
                    }
                }

                val apkFile = File(apkDir, "update.apk")
                if (apkFile.exists()) apkFile.delete()
                tempFile.renameTo(apkFile)
                Result.success(apkFile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 是否已获得「安装未知应用」权限（Android 8.0+ 需要） */
    fun hasInstallPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** 引导用户开启「安装未知应用」权限 */
    fun openInstallPermissionSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        } catch (e: Exception) {
        }
    }

    /** 调用系统安装器安装 APK；权限不足时返回 false */
    fun installApk(apkFile: File): Boolean {
        if (!hasInstallPermission()) return false
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 获取设备支持的 ABI，用于匹配下载链接 */
    private fun getDeviceAbi(): String {
        val supportedAbis = Build.SUPPORTED_ABIS

        val supportedSplits = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        return supportedAbis.firstOrNull { it in supportedSplits } ?: "universal"
    }

    /** 检查是否有新版本可用 */
    suspend fun checkUpdate(): UpdateStatus = withContext(Dispatchers.Default) {
        try {
            // 下载并解析 JSON
            val request = Request.Builder().url(UPDATE_REPO_URL).build()
            val indexJson = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载失败")
                response.body.string()
            }

            // 获取当前 Flavor 信息
            val updateIndex = json.decodeFromString<UpdateIndex>(indexJson)
            val flavorInfo = when (currentFlavorId) {
                "prod" -> updateIndex.prod
                "dev" -> updateIndex.dev
                else -> throw IllegalStateException("未知 Flavor ID")
            }

            // 比较版本代码
            if (flavorInfo.latestVersionCode <= currentVersionCode) {
                return@withContext UpdateStatus.Latest(BuildConfig.VERSION_NAME)
            }

            // 匹配设备 ABI
            val deviceAbi = getDeviceAbi()
            val finalDownloadUrl = flavorInfo.downloadLinks[deviceAbi]
                ?: flavorInfo.downloadLinks["universal"]
                ?: throw IllegalStateException("未找到适合 ABI 的下载链接")
            val checksum = flavorInfo.checksums[deviceAbi] ?: flavorInfo.checksums["universal"]

            return@withContext UpdateStatus.Found(flavorInfo, finalDownloadUrl, checksum)

        } catch (e: Exception) {
            return@withContext UpdateStatus.Error("检查更新失败: ${e.message ?: "未知错误"}")
        }
    }
}

/** 计算文件的 SHA-256 十六进制摘要 */
private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}