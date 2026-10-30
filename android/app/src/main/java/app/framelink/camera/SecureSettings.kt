package app.framelink.camera

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 相机认证和云端会话使用Android Keystore加密，禁用系统备份 */
interface SecureValueStore{
    fun put(name:String,value:String)
    fun get(name:String):String
    fun remove(name:String)
    fun contains(name:String):Boolean
    fun names():Set<String> = emptySet()
}

class SecureSettings(context: Context):SecureValueStore {
    private val preferences =
        context.getSharedPreferences("framelink.settings", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("framelink.credentials", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "framelink.credentials",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    override fun put(name: String, value: String) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        preferences
            .edit()
            .putString(
                name,
                Base64.encodeToString(
                    cipher.iv + cipher.doFinal(value.toByteArray()),
                    Base64.NO_WRAP,
                ),
            )
            .apply()
    }

    override fun get(name: String): String =
        runCatching {
                val raw = preferences.getString(name, null) ?: return ""
                val bytes = Base64.decode(raw, Base64.NO_WRAP)
                String(
                    Cipher.getInstance("AES/GCM/NoPadding")
                        .apply {
                            init(
                                Cipher.DECRYPT_MODE,
                                key,
                                GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                            )
                        }
                        .doFinal(bytes.copyOfRange(12, bytes.size))
                )
            }
            .getOrDefault("")

    override fun contains(name:String)=preferences.contains(name)
    override fun names():Set<String> = preferences.all.keys
    override fun remove(name: String) {
        preferences.edit().remove(name).apply()
    }
}
