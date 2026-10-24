package com.nurvpn.app.util

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val GITHUB_API = "https://api.github.com/repos/javoxir5543/NurVPN/releases/latest"

    fun checkForUpdates(activity: Activity) {
        Thread {
            try {
                val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val latestVersion = json.getString("tag_name").replace("v", "").trim()
                    val currentVersion = getCurrentVersion(activity)

                    if (isNewerVersion(latestVersion, currentVersion)) {
                        val assets = json.getJSONArray("assets")
                        var apkUrl: String? = null
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name").endsWith(".apk")) {
                                apkUrl = asset.getString("browser_download_url")
                                break
                            }
                        }

                        val finalApkUrl = apkUrl
                        if (finalApkUrl != null) {
                            activity.runOnUiThread {
                                showUpdateDialog(activity, latestVersion, finalApkUrl)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Xatolik bo'lsa jimgina o'tkazib yuboramiz
            }
        }.start()
    }

    private fun getCurrentVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) { "1.0" }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val c = current.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(l.size, c.size)) {
                val lPart = l.getOrElse(i) { 0 }
                val cPart = c.getOrElse(i) { 0 }
                if (lPart > cPart) return true
                if (lPart < cPart) return false
            }
            false
        } catch (e: Exception) { false }
    }

    private fun showUpdateDialog(activity: Activity, version: String, apkUrl: String) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(com.nurvpn.app.R.string.update_title))
            .setMessage(activity.getString(com.nurvpn.app.R.string.update_message, version))
            .setPositiveButton(activity.getString(com.nurvpn.app.R.string.update_btn_now)) { _, _ ->
                downloadAndInstall(activity, apkUrl)
            }
            .setNegativeButton(activity.getString(com.nurvpn.app.R.string.update_btn_later), null)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, apkUrl: String) {
        val progressDialog = ProgressDialog(activity).apply {
            setTitle(activity.getString(com.nurvpn.app.R.string.update_downloading))
            setMessage(activity.getString(com.nurvpn.app.R.string.update_wait))
            isIndeterminate = false
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }

        Thread {
            try {
                val url = URL(apkUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.connect()

                val fileLength = conn.contentLength
                val downloadDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (downloadDir != null && !downloadDir.exists()) downloadDir.mkdirs()
                val apkFile = File(downloadDir, "update.apk")
                if (apkFile.exists()) apkFile.delete()

                val input = conn.inputStream
                val output = FileOutputStream(apkFile)
                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int

                while (input.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        activity.runOnUiThread { progressDialog.progress = progress }
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()

                activity.runOnUiThread {
                    progressDialog.dismiss()
                    installApk(activity, apkFile)
                }

            } catch (e: Exception) {
                activity.runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(activity, activity.getString(com.nurvpn.app.R.string.update_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun installApk(activity: Activity, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(activity, activity.getString(com.nurvpn.app.R.string.update_unknown_sources), Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + activity.packageName))
                activity.startActivity(intent)
                return
            }
        }

        val uri: Uri = FileProvider.getUriForFile(
            activity,
            activity.packageName + ".fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
