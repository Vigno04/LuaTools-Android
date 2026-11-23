package com.luatools.companion.cloud

import android.content.Context
import com.cloudredirect.nativecore.CloudRedirectNative
import com.cloudredirect.nativecore.OkHttpCloudRedirectBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Android service wrapper around libcloud_redirect_android.so (CloudRedirectNative).
 */
class CloudRedirectService(private val context: Context) {

    @Volatile
    private var initialized = false

    val isNativeAvailable: Boolean
        get() = CloudRedirectNative.isLoaded

    fun ensureInitialized(): Boolean {
        if (!CloudRedirectNative.isLoaded) return false
        if (initialized) return true
        val ok = CloudRedirectNative.nativeInit(
            context.filesDir.absolutePath,
            OkHttpCloudRedirectBridge()
        )
        initialized = ok
        return ok
    }

    fun getNativeVersion(): String? {
        if (!ensureInitialized()) return null
        return CloudRedirectNative.nativeGetVersion()
    }

    suspend fun authStatus(provider: String): JSONObject = runCmd("auth-status", provider)

    suspend fun listRemoteApps(provider: String, accountId: String): JSONObject =
        runCmd("list-remote-apps", provider, accountId)

    suspend fun syncRemoteApp(
        provider: String,
        accountId: String,
        appId: String,
        cloudRoot: String
    ): JSONObject = runCmd("sync-remote-app", provider, accountId, appId, cloudRoot)

    suspend fun syncAllRemoteApps(
        provider: String,
        accountId: String,
        cloudRoot: String
    ): JSONObject = runCmd("sync-all-remote-apps", provider, accountId, cloudRoot)

    suspend fun publishFullManifest(
        provider: String,
        accountId: String,
        appId: String,
        cloudRoot: String
    ): JSONObject = runCmd("publish-full-manifest", provider, accountId, appId, cloudRoot)

    suspend fun discoverCloudGames(provider: String): JSONObject =
        runCmd("discover-cloud-games", provider)

    private suspend fun runCmd(command: String, vararg args: String): JSONObject =
        withContext(Dispatchers.IO) {
            if (!ensureInitialized()) {
                return@withContext JSONObject().apply {
                    put("success", false)
                    put("error", "libcloud_redirect_android.so not loaded (place in app/src/main/jniLibs)")
                }
            }
            val raw = CloudRedirectNative.nativeRunCommand(command, arrayOf(*args))
            try {
                JSONObject(raw)
            } catch (_: Exception) {
                JSONObject().apply {
                    put("success", false)
                    put("raw", raw)
                }
            }
        }
}
