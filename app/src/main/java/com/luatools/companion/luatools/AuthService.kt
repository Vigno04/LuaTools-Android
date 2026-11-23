package com.luatools.companion.luatools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Mirrors LuaToolsGui/Services/AuthService.cs (Supabase + Discord OAuth PKCE & 6-char bot code).
 */
class AuthService(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val prefs = context.getSharedPreferences("luatools_auth", Context.MODE_PRIVATE)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    val isSignedIn: Boolean
        get() = !prefs.getString("refresh_token", null).isNullOrEmpty()

    val displayName: String?
        get() = prefs.getString("display_name", null)

    val email: String?
        get() = prefs.getString("email", null)

    suspend fun getValidAccessTokenAsync(): String? = withContext(Dispatchers.IO) {
        val access = prefs.getString("access_token", null)
        val refresh = prefs.getString("refresh_token", null) ?: return@withContext null
        val expiresAt = prefs.getLong("expires_at", 0L)
        val nowSec = System.currentTimeMillis() / 1000L
        if (!access.isNullOrEmpty() && expiresAt > nowSec + 120L) {
            return@withContext access
        }
        refreshAsync(refresh)
    }

    suspend fun signInWithBotCodeAsync(code: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().put("code", code.trim().uppercase()).toString()
            val redeemReq = Request.Builder()
                .url("${AppConfig.API_BASE_URL}/api/auth/code/redeem")
                .post(payload.toRequestBody(jsonMedia))
                .build()

            val tokenHash = http.newCall(redeemReq).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw IllegalStateException("Code redeem failed (HTTP ${res.code}): $body")
                }
                JSONObject(body).optString("token")
            }
            require(tokenHash.isNotEmpty()) { "Empty magic token from server" }

            val verifyPayload = JSONObject()
                .put("type", "magiclink")
                .put("token_hash", tokenHash)
                .toString()
            val verifyReq = Request.Builder()
                .url("${AppConfig.SUPABASE_URL}/auth/v1/verify")
                .header("apikey", AppConfig.SUPABASE_ANON_KEY)
                .post(verifyPayload.toRequestBody(jsonMedia))
                .build()

            http.newCall(verifyReq).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw IllegalStateException("Supabase verify failed (HTTP ${res.code}): $body")
                }
                saveSession(JSONObject(body))
                displayName ?: email ?: "Signed in"
            }
        }
    }

    suspend fun signInWithDiscordPkceAsync(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val verifier = Base64.encodeToString(verifierBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val challengeBytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            val challenge = Base64.encodeToString(challengeBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

            val redirectUri = "http://localhost:53789/callback"
            val authUrl = "${AppConfig.SUPABASE_URL}/auth/v1/authorize?provider=discord" +
                "&redirect_to=${URLEncoder.encode(redirectUri, "UTF-8")}" +
                "&code_challenge=$challenge&code_challenge_method=s256"

            val server = ServerSocket(53789, 1, InetAddress.getByName("127.0.0.1"))
            server.soTimeout = 180_000
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                val code = waitForLoopbackCode(server)
                    ?: throw IllegalStateException("No authorization code received")

                val tokenBody = JSONObject()
                    .put("auth_code", code)
                    .put("code_verifier", verifier)
                    .toString()
                val req = Request.Builder()
                    .url("${AppConfig.SUPABASE_URL}/auth/v1/token?grant_type=pkce")
                    .header("apikey", AppConfig.SUPABASE_ANON_KEY)
                    .post(tokenBody.toRequestBody(jsonMedia))
                    .build()

                http.newCall(req).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        throw IllegalStateException("Token exchange failed (HTTP ${res.code}): $body")
                    }
                    saveSession(JSONObject(body))
                    displayName ?: email ?: "Signed in"
                }
            } finally {
                runCatching { server.close() }
            }
        }
    }

    fun signOut() {
        prefs.edit().clear().apply()
    }

    private fun refreshAsync(refreshToken: String): String? {
        val body = JSONObject().put("refresh_token", refreshToken).toString()
        val req = Request.Builder()
            .url("${AppConfig.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token")
            .header("apikey", AppConfig.SUPABASE_ANON_KEY)
            .post(body.toRequestBody(jsonMedia))
            .build()

        return try {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val json = JSONObject(res.body?.string() ?: return null)
                saveSession(json)
                json.optString("access_token").takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveSession(json: JSONObject) {
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val expiresIn = json.optLong("expires_in", 3600L)
        val user = json.optJSONObject("user")
        val meta = user?.optJSONObject("user_metadata")
        val name = meta?.optString("full_name")
            ?.ifEmpty { meta.optString("user_name") }
            ?.ifEmpty { user.optString("email") }
        val userEmail = user?.optString("email")

        prefs.edit()
            .putString("access_token", access)
            .putString("refresh_token", refresh)
            .putLong("expires_at", (System.currentTimeMillis() / 1000L) + expiresIn)
            .putString("display_name", name)
            .putString("email", userEmail)
            .apply()
    }

    private fun waitForLoopbackCode(server: ServerSocket): String? {
        while (true) {
            server.accept().use { sock ->
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val requestLine = reader.readLine() ?: return null
                val path = requestLine.split(" ").getOrNull(1).orEmpty()
                if (!path.startsWith("/callback")) {
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return@use
                }
                val query = path.substringAfter("?", "")
                val params = query.split("&").mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null
                    else URLDecoder.decode(part.substring(0, idx), "UTF-8") to
                        URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                }.toMap()

                val code = params["code"]
                val html = if (!code.isNullOrEmpty()) {
                    "<html><body style='font-family:sans-serif;background:#121212;color:#fff;text-align:center;padding:48px'><h2>LuaTools Signed In</h2><p>You can return to LuaTools Android now.</p></body></html>"
                } else {
                    "<html><body style='font-family:sans-serif;background:#121212;color:#fff;text-align:center;padding:48px'><h2>Sign-in Failed</h2><p>${params["error"] ?: "Unknown error"}</p></body></html>"
                }
                val bytes = html.toByteArray(Charsets.UTF_8)
                val out = sock.getOutputStream()
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
                )
                out.write(bytes)
                out.flush()
                return code
            }
        }
    }
}
