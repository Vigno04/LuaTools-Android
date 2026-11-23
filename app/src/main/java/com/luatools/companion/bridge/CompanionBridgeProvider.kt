package com.luatools.companion.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.luatools.companion.cloud.CloudRedirectService
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Exported ContentProvider (authority: com.luatools.companion.bridge) called by
 * BhLuaCloudHook inside BannerHub (and any other integrated emulator) on game launch and exit.
 *
 * Uses private staging under filesDir/cloud_cache + ParcelFileDescriptor streaming
 * so cross-app Android sandboxing never blocks save sync.
 */
class CompanionBridgeProvider : ContentProvider() {

    companion object {
        private const val TAG = "CompanionBridge"
        const val AUTHORITY = "com.luatools.companion.bridge"
        const val PREFS_NAME = "companion_bridge_prefs"
        const val KEY_PROVIDER = "active_provider"
        const val KEY_ACCOUNT_ID = "active_account_id"

        private val INTERNAL_NAMES = setOf(
            "manifest.cloudredirect",
            "manifest.dat",
            "cn.cloudredirect",
            "remotecache.vdf"
        )
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("No context")
        val segments = uri.pathSegments
        if (segments.size < 2) throw FileNotFoundException("Invalid bridge path: $uri")
        val kind = segments[0]
        val appId = segments[1].toIntOrNull() ?: throw FileNotFoundException("Invalid appId: ${segments[1]}")

        return when (kind) {
            "cloud_out" -> {
                val f = File(ctx.cacheDir, "cloud_out_$appId.zip")
                if (!f.isFile) throw FileNotFoundException("No cloud_out archive for $appId")
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            "cloud_in" -> {
                val f = File(ctx.cacheDir, "cloud_in_$appId.zip")
                ParcelFileDescriptor.open(
                    f,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY
                )
            }
            else -> throw FileNotFoundException("Unknown path kind: $kind")
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle().apply { putBoolean("ok", false) }
        val res = Bundle()

        when (method) {
            "ping" -> {
                res.putBoolean("ok", true)
                res.putString("companion", "LuaToolsAndroid")
                res.putInt("bridge_version", 2)
                return res
            }

            "sync_pre_launch" -> {
                val appId = extras?.getInt("app_id", 0) ?: arg?.toIntOrNull() ?: 0
                if (appId <= 0) {
                    res.putBoolean("ok", false)
                    res.putString("error", "Invalid app_id")
                    return res
                }

                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val provider = prefs.getString(KEY_PROVIDER, "gdrive") ?: "gdrive"
                val accountId = prefs.getString(KEY_ACCOUNT_ID, "0") ?: "0"

                val cloudService = CloudRedirectService(ctx)
                if (!cloudService.isNativeAvailable) {
                    Log.w(TAG, "sync_pre_launch skipped: native CloudRedirect library not loaded")
                    res.putBoolean("ok", false)
                    res.putString("error", "Native library not loaded")
                    return res
                }

                val cloudRoot = File(ctx.filesDir, "cloud_cache").apply { mkdirs() }
                val json = runBlocking {
                    cloudService.syncRemoteApp(provider, accountId, appId.toString(), cloudRoot.absolutePath)
                }

                val appStorageDir = File(cloudRoot, "storage/$accountId/$appId")
                val outZip = File(ctx.cacheDir, "cloud_out_$appId.zip")
                val fileCount = if (appStorageDir.isDirectory) {
                    zipSaveFiles(appStorageDir, outZip)
                } else {
                    0
                }

                res.putBoolean("ok", json.optBoolean("success", false))
                res.putBoolean("has_files", fileCount > 0)
                res.putInt("file_count", fileCount)
                if (fileCount > 0) {
                    res.putString("zip_uri", "content://$AUTHORITY/cloud_out/$appId")
                }
                res.putString("result_json", json.toString())
                Log.i(TAG, "sync_pre_launch app=$appId ok=${json.optBoolean("success", false)} files=$fileCount")
                return res
            }

            "sync_post_exit" -> {
                val appId = extras?.getInt("app_id", 0) ?: arg?.toIntOrNull() ?: 0
                if (appId <= 0) {
                    res.putBoolean("ok", false)
                    res.putString("error", "Invalid app_id")
                    return res
                }

                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val provider = prefs.getString(KEY_PROVIDER, "gdrive") ?: "gdrive"
                val accountId = prefs.getString(KEY_ACCOUNT_ID, "0") ?: "0"

                val cloudService = CloudRedirectService(ctx)
                if (!cloudService.isNativeAvailable) {
                    Log.w(TAG, "sync_post_exit skipped: native CloudRedirect library not loaded")
                    res.putBoolean("ok", false)
                    res.putString("error", "Native library not loaded")
                    return res
                }

                val cloudRoot = File(ctx.filesDir, "cloud_cache").apply { mkdirs() }
                val appStorageDir = File(cloudRoot, "storage/$accountId/$appId").apply { mkdirs() }

                val inZip = File(ctx.cacheDir, "cloud_in_$appId.zip")
                var unpacked = 0
                if (inZip.isFile && inZip.length() > 0) {
                    unpacked = unzipSaveFiles(inZip, appStorageDir)
                    inZip.delete()
                }

                val json = runBlocking {
                    cloudService.publishFullManifest(provider, accountId, appId.toString(), cloudRoot.absolutePath)
                }
                res.putBoolean("ok", json.optBoolean("success", false))
                res.putInt("unpacked_files", unpacked)
                res.putString("result_json", json.toString())
                Log.i(TAG, "sync_post_exit app=$appId unpacked=$unpacked ok=${json.optBoolean("success", false)}")
                return res
            }

            else -> {
                res.putBoolean("ok", false)
                res.putString("error", "Unknown method: $method")
                return res
            }
        }
    }

    private val sha1NameRegex = Regex("^[0-9a-fA-F]{40}$")

    private fun isCloudRedirectInternal(name: String): Boolean {
        val low = name.lowercase()
        return low.startsWith(".") ||
            low in INTERNAL_NAMES ||
            low.endsWith(".cloudredirect")
    }

    /**
     * CloudRedirect stores each cloud file as a content-addressed directory:
     *   storage/<accountId>/<appId>/<filename>/<40-hex-sha1>
     * Flatten those into real save files (<filename>) inside the zip sent to GSE Saves/<appId>/remote/.
     */
    private fun zipSaveFiles(rootDir: File, outZip: File): Int {
        var count = 0
        val buf = ByteArray(8192)
        ZipOutputStream(FileOutputStream(outZip)).use { zos ->
            fun writeEntry(relPath: String, file: File) {
                zos.putNextEntry(ZipEntry(relPath))
                FileInputStream(file).use { fis ->
                    var r: Int
                    while (fis.read(buf).also { r = it } != -1) {
                        zos.write(buf, 0, r)
                    }
                }
                zos.closeEntry()
                count++
            }

            fun walk(dir: File) {
                val children = dir.listFiles() ?: return
                for (f in children) {
                    if (isCloudRedirectInternal(f.name)) continue
                    if (f.isDirectory) {
                        val sub = f.listFiles().orEmpty().filter { !isCloudRedirectInternal(it.name) }
                        val shaBlobs = sub.filter { it.isFile && sha1NameRegex.matches(it.name) }
                        if (shaBlobs.isNotEmpty() && shaBlobs.size == sub.size) {
                            val newest = shaBlobs.maxByOrNull { it.lastModified() }!!
                            val rel = f.relativeTo(rootDir).path.replace('\\', '/')
                            writeEntry(rel, newest)
                        } else {
                            walk(f)
                        }
                    } else if (f.isFile) {
                        val rel = f.relativeTo(rootDir).path.replace('\\', '/')
                        writeEntry(rel, f)
                    }
                }
            }
            walk(rootDir)
        }
        if (count == 0) outZip.delete()
        return count
    }

    /**
     * Unpacks flat save files from GSE Saves/<appId>/remote/ into CloudRedirect's
     * content-addressed storage layout: storage/<accountId>/<appId>/<relPath>/<sha1>.
     */
    private fun unzipSaveFiles(zipFile: File, destDir: File): Int {
        var count = 0
        val canonicalDest = destDir.canonicalPath + File.separator
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (name.isNotEmpty() && !name.startsWith("/") && !name.contains("..") && !entry.isDirectory) {
                    val baseName = File(name).name
                    if (!isCloudRedirectInternal(baseName)) {
                        val bytes = zis.readBytes()
                        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
                            .digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                        val blobDir = File(destDir, name)
                        if (blobDir.canonicalPath.startsWith(canonicalDest)) {
                            if (blobDir.isFile) blobDir.delete()
                            blobDir.mkdirs()
                            // Replace older SHA-1 blob(s) for this file
                            blobDir.listFiles()?.forEach { old ->
                                if (old.isFile && sha1NameRegex.matches(old.name) && old.name != sha1) {
                                    old.delete()
                                }
                            }
                            File(blobDir, sha1).writeBytes(bytes)
                            count++
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return count
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
