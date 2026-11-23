package com.luatools.companion.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Mirrors CloudRedirect/ui/Services/OAuthService.cs for Google Drive & OneDrive
 * loopback OAuth2 PKCE on Android, writing tokens directly where
 * libcloud_redirect_android.so expects them.
 */
class CloudRedirectOAuthService(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val GDRIVE_CLIENT_ID =
            "1072944905499-vm2v2i5dvn0a0d2o4ca36i1vge8cvbn0.apps.googleusercontent.com"
        private const val GDRIVE_CLIENT_SECRET = "v6V3fKV_zWU7iw1DrpO1rknX"
        private const val GDRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val GDRIVE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val GDRIVE_TOKEN_URL = "https://oauth2.googleapis.com/token"

        private const val ONEDRIVE_CLIENT_ID = "b15665d9-eda6-4092-8539-0eec376afd59"
        private const val ONEDRIVE_CLIENT_SECRET = "qtyfaBBYA403=unZUP40~_#"
        private const val ONEDRIVE_SCOPE = "Files.ReadWrite offline_access"
        private const val ONEDRIVE_AUTH_URL =
            "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
        private const val ONEDRIVE_TOKEN_URL =
            "https://login.microsoftonline.com/common/oauth2/v2.0/token"
        private const val ONEDRIVE_PORT = 53682
    }

    suspend fun authorizeAsync(provider: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val lower = provider.lowercase()
            require(lower == "gdrive" || lower == "onedrive") {
                "OAuth login is only for gdrive or onedrive (got $provider)"
            }

            val state = randomUrlSafe(24)
            val verifier = randomUrlSafe(48)
            val challengeBytes = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            val challenge = Base64.encodeToString(
                challengeBytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            val port = if (lower == "onedrive") ONEDRIVE_PORT else 0
            val server = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
            server.soTimeout = 180_000

            try {
                val actualPort = server.localPort
                val redirectUri = if (lower == "onedrive") {
                    "http://localhost:$actualPort/callback"
                } else {
                    "http://127.0.0.1:$actualPort/callback"
                }

                val authUrl = if (lower == "onedrive") {
                    "$ONEDRIVE_AUTH_URL?client_id=${URLEncoder.encode(ONEDRIVE_CLIENT_ID, "UTF-8")}" +
                        "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}" +
                        "&response_type=code&scope=${URLEncoder.encode(ONEDRIVE_SCOPE, "UTF-8")}" +
                        "&prompt=consent&state=${URLEncoder.encode(state, "UTF-8")}" +
                        "&code_challenge=$challenge&code_challenge_method=S256"
                } else {
                    "$GDRIVE_AUTH_URL?client_id=${URLEncoder.encode(GDRIVE_CLIENT_ID, "UTF-8")}" +
                        "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}" +
                        "&response_type=code&scope=${URLEncoder.encode(GDRIVE_SCOPE, "UTF-8")}" +
                        "&access_type=offline&prompt=consent&state=${URLEncoder.encode(state, "UTF-8")}" +
                        "&code_challenge=$challenge&code_challenge_method=S256"
                }

                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                val code = waitForCallback(server, state)
                    ?: throw IllegalStateException("Authorization cancelled or timed out")

                val tokenJson = exchangeCode(lower, code, redirectUri, verifier)
                saveTokenAndConfig(lower, tokenJson)
                "Authenticated $lower successfully"
            } finally {
                runCatching { server.close() }
            }
        }
    }

    private fun exchangeCode(
        provider: String,
        code: String,
        redirectUri: String,
        verifier: String
    ): JSONObject {
        val form = FormBody.Builder()
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")
            .add("code_verifier", verifier)
            .apply {
                if (provider == "onedrive") {
                    add("client_id", ONEDRIVE_CLIENT_ID)
                    add("client_secret", ONEDRIVE_CLIENT_SECRET)
                    add("scope", ONEDRIVE_SCOPE)
                } else {
                    add("client_id", GDRIVE_CLIENT_ID)
                    add("client_secret", GDRIVE_CLIENT_SECRET)
                }
            }
            .build()

        val tokenUrl = if (provider == "onedrive") ONEDRIVE_TOKEN_URL else GDRIVE_TOKEN_URL
        val req = Request.Builder().url(tokenUrl).post(form).build()

        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw IllegalStateException("Token exchange failed (HTTP ${res.code}): $body")
            }
            val parsed = JSONObject(body)
            val expiresIn = parsed.optLong("expires_in", 3600L)
            val expiresAt = (System.currentTimeMillis() / 1000L) + expiresIn
            return JSONObject().apply {
                put("access_token", parsed.optString("access_token"))
                put("refresh_token", parsed.optString("refresh_token"))
                put("expires_in", expiresIn)
                put("expires_at", expiresAt)
                put("token_type", parsed.optString("token_type", "Bearer"))
            }
        }
    }

    private fun saveTokenAndConfig(provider: String, tokenJson: JSONObject) {
        val crDir = File(context.filesDir, "CloudRedirect").apply { mkdirs() }
        val tokenFile = File(crDir, "tokens_$provider.json")
        tokenFile.writeText(tokenJson.toString(2), Charsets.UTF_8)

        val configFile = File(crDir, "config.json")
        val cfg = if (configFile.exists()) {
            runCatching { JSONObject(configFile.readText(Charsets.UTF_8)) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        cfg.put("provider", provider)
        val tokenPaths = cfg.optJSONObject("token_paths") ?: JSONObject()
        tokenPaths.put(provider, tokenFile.absolutePath)
        cfg.put("token_paths", tokenPaths)
        configFile.writeText(cfg.toString(2), Charsets.UTF_8)
    }

    private fun waitForCallback(server: ServerSocket, expectedState: String): String? {
        while (true) {
            server.accept().use { sock ->
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val reqLine = reader.readLine() ?: return null
                val path = reqLine.split(" ").getOrNull(1).orEmpty()
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

                val stateOk = params["state"] == expectedState
                val code = if (stateOk) params["code"] else null
                val html = if (!code.isNullOrEmpty()) {
                    "<html><body style='font-family:sans-serif;background:#121212;color:#fff;text-align:center;padding:48px'><h2>CloudRedirect Authorized</h2><p>You can return to LuaTools Android now.</p></body></html>"
                } else {
                    "<html><body style='font-family:sans-serif;background:#121212;color:#fff;text-align:center;padding:48px'><h2>Authorization Failed</h2><p>${params["error"] ?: "state_mismatch"}</p></body></html>"
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

    private fun randomUrlSafe(len: Int): String {
        val bytes = ByteArray(len).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .take(len)
    }
}
