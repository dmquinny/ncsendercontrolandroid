package com.cncpendant.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("name") val name: String,
    @SerializedName("body") val body: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("assets") val assets: List<GitHubAsset>
)

data class GitHubAsset(
    @SerializedName("name") val name: String,
    @SerializedName("browser_download_url") val downloadUrl: String
)

object UpdateChecker {
    private const val GITHUB_API_URL = "https://api.github.com/repos/dmquinny/ncsendercontrolandroid/releases/latest"
    private const val PREFS_NAME = "update_prefs"
    private const val KEY_SKIPPED_VERSION = "skipped_version"
    private const val TAG = "UpdateChecker"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    suspend fun checkForUpdate(context: Context): GitHubRelease? {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d(TAG, "Checking for updates...")
                val request = Request.Builder()
                    .url(GITHUB_API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                
                val response = client.newCall(request).execute()
                android.util.Log.d(TAG, "Response code: ${response.code}")
                if (response.isSuccessful) {
                    response.body?.string()?.let { json ->
                        val release = gson.fromJson(json, GitHubRelease::class.java)
                        val latestVersion = parseVersion(release.tagName)
                        val currentVersion = parseVersion(getCurrentVersion(context))
                        
                        android.util.Log.d(TAG, "Current version: ${getCurrentVersion(context)} ($currentVersion)")
                        android.util.Log.d(TAG, "Latest version: ${release.tagName} ($latestVersion)")
                        
                        if (latestVersion > currentVersion) {
                            // Check if user skipped this version
                            val skippedVersion = getSkippedVersion(context)
                            android.util.Log.d(TAG, "Skipped version: $skippedVersion")
                            if (release.tagName != skippedVersion) {
                                android.util.Log.d(TAG, "Update available!")
                                return@withContext release
                            } else {
                                android.util.Log.d(TAG, "User skipped this version")
                            }
                        } else {
                            android.util.Log.d(TAG, "Already on latest version")
                        }
                    }
                } else {
                    android.util.Log.e(TAG, "Response failed: ${response.code} ${response.message}")
                }
                null
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error checking for updates", e)
                e.printStackTrace()
                null
            }
        }
    }
    
    fun showUpdateDialog(context: Context, release: GitHubRelease) {
        val apkAsset = release.assets.find { it.name.endsWith(".apk") }
        
        val message = buildString {
            append("A new version is available!\n\n")
            append("Current: v${getCurrentVersion(context)}\n")
            append("Latest: ${release.tagName}\n\n")
            if (release.body.isNotBlank()) {
                append("What's new:\n")
                append(release.body.take(500))
                if (release.body.length > 500) append("...")
            }
        }
        
        val builder = AlertDialog.Builder(context, R.style.DarkAlertDialog)
            .setTitle("Update Available")
            .setMessage(message)
            .setPositiveButton("Download") { _, _ ->
                val url = apkAsset?.downloadUrl ?: release.htmlUrl
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .setNeutralButton("Skip Version") { _, _ ->
                setSkippedVersion(context, release.tagName)
            }
        
        builder.show()
    }
    
    private fun getCurrentVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }
    
    private fun parseVersion(version: String): Long {
        // Remove 'v' prefix if present and parse version like "1.2.3" to comparable number
        val cleaned = version.removePrefix("v").removePrefix("V")
        val parts = cleaned.split(".").take(3)
        return try {
            val major = parts.getOrNull(0)?.toLongOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toLongOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toLongOrNull() ?: 0
            major * 1000000 + minor * 1000 + patch
        } catch (e: Exception) {
            0
        }
    }
    
    private fun getSkippedVersion(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SKIPPED_VERSION, null)
    }
    
    private fun setSkippedVersion(context: Context, version: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SKIPPED_VERSION, version).apply()
    }
}
