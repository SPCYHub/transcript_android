package com.example.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.ai.ProviderRequestAuth
import com.example.model.ApiKeyStatus
import com.example.model.KeyTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "SecureKeyStore"
private const val PREFS_NAME = "cantoscribe_secure_keys"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS = "cantoscribe_api_keys_aes_v1"
private const val GCM_TAG_BITS = 128

private const val KEY_GEMINI_ENC = "enc_gemini_api_key"
private const val KEY_OPENAI_ENC = "enc_openai_api_key"
private const val KEY_USE_PERSONAL_GEMINI = "use_personal_gemini_key"
private const val KEY_CUSTOM_GEMINI_MODEL = "custom_gemini_model"
private const val OPENAI_PLACEHOLDER = "MY_OPENAI_API_KEY"

/**
 * Hardware-backed Android Keystore (AES-256-GCM) vault for end-user API keys.
 *
 * - Keys are encrypted at rest before being stored in private app SharedPreferences
 *   (excluded from cloud backup & device transfer).
 * - Never logs raw API keys.
 * - Allows users running exported/sideloaded APKs to supply their own Gemini API key
 *   (bypassing Firebase App Check) and/or OpenAI API key directly at runtime.
 */
class SecureKeyStore(appContext: Context) {

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val verifyClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val _status = MutableStateFlow(buildStatusSnapshot())
    val status: StateFlow<ApiKeyStatus> = _status.asStateFlow()

    fun saveGeminiApiKey(rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            clearGeminiApiKey()
            return
        }
        val encrypted = encryptValue(trimmed)
        prefs.edit()
            .putString(KEY_GEMINI_ENC, encrypted)
            .putBoolean(KEY_USE_PERSONAL_GEMINI, true)
            .apply()
        refreshStatus()
    }

    fun clearGeminiApiKey() {
        prefs.edit()
            .remove(KEY_GEMINI_ENC)
            .apply()
        refreshStatus()
    }

    fun setUsePersonalGeminiKey(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_USE_PERSONAL_GEMINI, enabled)
            .apply()
        refreshStatus()
    }

    fun saveCustomGeminiModel(modelName: String) {
        val clean = modelName.trim()
        prefs.edit().apply {
            if (clean.isEmpty()) {
                remove(KEY_CUSTOM_GEMINI_MODEL)
            } else {
                val safeModel = ProviderRequestAuth.sanitizeGeminiModelName(clean)
                putString(KEY_CUSTOM_GEMINI_MODEL, safeModel)
            }
        }.apply()
        refreshStatus()
    }

    fun saveOpenAiApiKey(rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            clearOpenAiApiKey()
            return
        }
        val encrypted = encryptValue(trimmed)
        prefs.edit()
            .putString(KEY_OPENAI_ENC, encrypted)
            .apply()
        refreshStatus()
    }

    fun clearOpenAiApiKey() {
        prefs.edit()
            .remove(KEY_OPENAI_ENC)
            .apply()
        refreshStatus()
    }

    /**
     * Returns the user's saved Gemini API key (encrypted locally with Android Keystore).
     * Never falls back to Firebase.
     */
    fun resolveDirectGeminiKey(): String? {
        return readDecrypted(KEY_GEMINI_ENC)?.takeIf { it.isNotBlank() }
    }

    fun hasDirectGeminiKey(): Boolean = !resolveDirectGeminiKey().isNullOrBlank()

    fun resolveCustomGeminiModel(): String? {
        val raw = prefs.getString(KEY_CUSTOM_GEMINI_MODEL, null)?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return if (ProviderRequestAuth.isValidGeminiModelName(raw)) {
            ProviderRequestAuth.sanitizeGeminiModelName(raw)
        } else {
            null
        }
    }

    /**
     * Returns the user's personal OpenAI key if configured; otherwise falls back to BuildConfig.OPENAI_API_KEY.
     */
    fun resolveEffectiveOpenAiKey(): String? {
        val userKey = readDecrypted(KEY_OPENAI_ENC)?.trim()
        if (!userKey.isNullOrEmpty()) return userKey
        val buildKey = BuildConfig.OPENAI_API_KEY.trim()
        return buildKey.takeIf { isValidNonPlaceholderKey(it) }
    }

    fun hasEffectiveOpenAiKey(): Boolean = !resolveEffectiveOpenAiKey().isNullOrBlank()

    suspend fun verifyGeminiKey(candidateKey: String? = null): KeyTestResult = withContext(Dispatchers.IO) {
        val keyToTest = candidateKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: readDecrypted(KEY_GEMINI_ENC)
            ?: return@withContext KeyTestResult.Invalid("Enter a Gemini API key first.")

        try {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=5")
                .header(ProviderRequestAuth.GEMINI_API_KEY_HEADER, keyToTest)
                .get()
                .build()

            verifyClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val count = runCatching {
                        JSONObject(body).optJSONArray("models")?.length() ?: 0
                    }.getOrDefault(0)
                    KeyTestResult.Valid(
                        if (count > 0) "Verified · Gemini API ready ($count+ models accessible)"
                        else "Verified · Gemini API key accepted"
                    )
                } else {
                    val msg = runCatching {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    KeyTestResult.Invalid(
                        ProviderRequestAuth.redactSecrets(msg).ifBlank {
                            "Gemini API rejected key (HTTP ${response.code})"
                        }
                    )
                }
            }
        } catch (e: Exception) {
            val safeError = ProviderRequestAuth.redactSecrets(e.localizedMessage)
                .ifBlank { "Network error while verifying Gemini key" }
            Log.e(TAG, "Gemini key verification failed: $safeError")
            KeyTestResult.Invalid(safeError)
        }
    }

    suspend fun verifyOpenAiKey(candidateKey: String? = null): KeyTestResult = withContext(Dispatchers.IO) {
        val keyToTest = candidateKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: resolveEffectiveOpenAiKey()
            ?: return@withContext KeyTestResult.Invalid("Enter an OpenAI API key first.")

        try {
            val request = Request.Builder()
                .url("https://api.openai.com/v1/models")
                .header("Authorization", ProviderRequestAuth.openAiAuthorization(keyToTest))
                .get()
                .build()

            verifyClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    KeyTestResult.Valid("Verified · OpenAI API key active")
                } else {
                    val msg = runCatching {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    KeyTestResult.Invalid(
                        ProviderRequestAuth.redactSecrets(msg).ifBlank {
                            "OpenAI rejected key (HTTP ${response.code})"
                        }
                    )
                }
            }
        } catch (e: Exception) {
            val safeError = ProviderRequestAuth.redactSecrets(e.localizedMessage)
                .ifBlank { "Network error while verifying OpenAI key" }
            Log.e(TAG, "OpenAI key verification failed: $safeError")
            KeyTestResult.Invalid(safeError)
        }
    }

    private fun refreshStatus() {
        _status.value = buildStatusSnapshot()
    }

    private fun buildStatusSnapshot(): ApiKeyStatus {
        val userGemini = readDecrypted(KEY_GEMINI_ENC)
        val userOpenAi = readDecrypted(KEY_OPENAI_ENC)
        val buildOpenAi = BuildConfig.OPENAI_API_KEY.trim()
        return ApiKeyStatus(
            hasUserGeminiKey = !userGemini.isNullOrBlank(),
            maskedGeminiKey = userGemini?.let(::maskKey),
            usePersonalGeminiKey = prefs.getBoolean(KEY_USE_PERSONAL_GEMINI, true),
            customGeminiModel = prefs.getString(KEY_CUSTOM_GEMINI_MODEL, "").orEmpty(),
            hasUserOpenAiKey = !userOpenAi.isNullOrBlank(),
            maskedOpenAiKey = userOpenAi?.let(::maskKey),
            hasBuildOpenAiKey = isValidNonPlaceholderKey(buildOpenAi)
        )
    }

    private fun isValidNonPlaceholderKey(value: String): Boolean {
        return value.isNotEmpty() &&
            value != OPENAI_PLACEHOLDER &&
            !value.startsWith("YOUR_")
    }

    private fun maskKey(raw: String): String {
        val clean = raw.trim()
        if (clean.length <= 8) return "••••••••"
        val prefix = clean.take(4)
        val suffix = clean.takeLast(4)
        return "$prefix••••••••$suffix"
    }

    private fun readDecrypted(prefKey: String): String? {
        val stored = prefs.getString(prefKey, null) ?: return null
        return decryptValue(stored)
    }

    private fun isRobolectricRuntime(): Boolean {
        return Build.FINGERPRINT.equals("robolectric", ignoreCase = true) ||
            Build.HARDWARE.equals("robolectric", ignoreCase = true)
    }

    private fun encryptValue(plainText: String): String {
        return try {
            encryptWithKeyStore(plainText)
        } catch (firstError: Exception) {
            if (isRobolectricRuntime()) {
                Log.w(TAG, "AndroidKeyStore unavailable in Robolectric runtime; using test encoding")
                return "b64:" + Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }
            // If an existing KeyStore entry became invalidated, clear and regenerate once cleanly.
            try {
                deleteKeyStoreKey()
                encryptWithKeyStore(plainText)
            } catch (retryError: Exception) {
                Log.e(TAG, "Hardware-backed KeyStore encryption failed: ${retryError.javaClass.simpleName}")
                throw IllegalStateException("Secure device keystore is unavailable for API key storage.", retryError)
            }
        }
    }

    private fun encryptWithKeyStore(plainText: String): String {
        val secretKey = getOrCreateKeyStoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + cipherText.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherText, 0, combined, 1 + iv.size, cipherText.size)
        return "ks1:" + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptValue(encoded: String): String? {
        return try {
            when {
                encoded.startsWith("ks1:") -> {
                    val payload = Base64.decode(encoded.removePrefix("ks1:"), Base64.NO_WRAP)
                    if (payload.size < 2) return null
                    val ivLen = payload[0].toInt() and 0xFF
                    if (payload.size <= 1 + ivLen) return null
                    val iv = payload.copyOfRange(1, 1 + ivLen)
                    val cipherText = payload.copyOfRange(1 + ivLen, payload.size)
                    val secretKey = getOrCreateKeyStoreKey()
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                    String(cipher.doFinal(cipherText), Charsets.UTF_8)
                }
                encoded.startsWith("b64:") -> {
                    // Fail closed on real Android devices: never trust unencrypted b64 entries outside tests.
                    if (!isRobolectricRuntime()) return null
                    String(Base64.decode(encoded.removePrefix("b64:"), Base64.NO_WRAP), Charsets.UTF_8)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed decrypting stored API key: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun deleteKeyStoreKey() {
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun getOrCreateKeyStoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
