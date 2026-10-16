package com.example.quadbrowser

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização pelo próprio APK: consulta a última Release do GitHub, compara a
 * tag (ex.: "v12") com o versionCode instalado e baixa/instala o .apk novo.
 * O repositório precisa ser PÚBLICO para funcionar sem token.
 */
object Updater {
    const val REPO = "viniciusy30-commits/cuddly-invention"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS = "updater_prefs"
    private const val KEY_LAST_CHECK = "last_auto_check"
    private const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

    private val main = Handler(Looper.getMainLooper())

    data class Release(val versionCode: Long, val tag: String, val apkUrl: String, val notes: String)

    private sealed class Result {
        data class NewVersion(val release: Release) : Result()
        object UpToDate : Result()
        object NoInternet : Result()
        object Error : Result()
    }

    fun installedVersionCode(context: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    } catch (_: Exception) {
        0L
    }

    fun installedVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Ao abrir o app: no máximo a cada 6 horas, e só avisa se tiver versão nova. */
    fun autoCheck(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < AUTO_CHECK_INTERVAL_MS) return
        fetch(activity) { result ->
            if (result is Result.NewVersion) {
                // Só marca o horário quando a consulta funcionou (sem internet tenta de novo na próxima).
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                if (!activity.isFinishing) showUpdateDialog(activity, result.release)
            } else if (result is Result.UpToDate) {
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            }
        }
    }

    /** Botão "Verificar atualização": mostra mensagens de procurando / já está na mais nova / sem internet. */
    fun manualCheck(activity: Activity) {
        Toast.makeText(activity, R.string.update_searching, Toast.LENGTH_SHORT).show()
        fetch(activity) { result ->
            if (activity.isFinishing) return@fetch
            when (result) {
                is Result.NewVersion -> showUpdateDialog(activity, result.release)
                Result.UpToDate -> Toast.makeText(activity, R.string.update_up_to_date, Toast.LENGTH_LONG).show()
                Result.NoInternet -> Toast.makeText(activity, R.string.update_no_internet, Toast.LENGTH_LONG).show()
                Result.Error -> Toast.makeText(activity, R.string.update_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fetch(activity: Activity, onResult: (Result) -> Unit) {
        val installed = installedVersionCode(activity)
        Thread {
            val result = try {
                val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "MultiAccount-Updater")
                }
                try {
                    if (conn.responseCode != 200) {
                        Result.Error
                    } else {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val tag = json.optString("tag_name")
                        val remote = tag.removePrefix("v").removePrefix("V").trim().toLongOrNull()
                        var apk: String? = null
                        val assets = json.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.getJSONObject(i)
                                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                                    apk = asset.optString("browser_download_url")
                                    break
                                }
                            }
                        }
                        if (remote == null || apk.isNullOrBlank()) {
                            Result.Error
                        } else if (remote > installed) {
                            Result.NewVersion(Release(remote, tag, apk, json.optString("body")))
                        } else {
                            Result.UpToDate
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: java.io.IOException) {
                Result.NoInternet
            } catch (_: Exception) {
                Result.Error
            }
            main.post { onResult(result) }
        }.start()
    }

    private fun showUpdateDialog(activity: Activity, release: Release) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(activity.getString(R.string.update_available_message, release.tag, installedVersionName(activity)))
            .setPositiveButton(R.string.update_action) { _, _ -> download(activity, release) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun download(activity: Activity, release: Release) {
        if (!canInstall(activity)) {
            showInstallPermissionDialog(activity)
            return
        }
        val progress = ProgressDialog(activity).apply {
            setTitle(R.string.update_downloading)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = true
            max = 100
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "update.apk")
                val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "MultiAccount-Updater")
                }
                try {
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var done = 0L
                            var lastPercent = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (total > 0) {
                                    val percent = (done * 100 / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        main.post {
                                            progress.isIndeterminate = false
                                            progress.progress = percent
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                main.post {
                    progress.dismiss()
                    if (!activity.isFinishing) install(activity, target)
                }
            } catch (_: Exception) {
                main.post {
                    progress.dismiss()
                    Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun showInstallPermissionDialog(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setPositiveButton(R.string.update_permission_open) { _, _ ->
                try {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                    )
                } catch (_: Exception) {
                    activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun install(activity: Activity, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
        }
    }
}
