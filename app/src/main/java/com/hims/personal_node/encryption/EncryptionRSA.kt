package com.hims.personal_node

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.*
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec.F4
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.security.auth.x500.X500Principal

/**
 * String encryption/decryption helper with system generated key.
 *
 * Results generated by this class are very difficult but not impossible to break. Since Android is
 * easy to decompile and attacker knows how the key generation and usage is implemented. It means
 * replay attack is still possible so attackers have a reliable chance better than brute force.
 *
 * Therefore, do not plant any values on this class which maybe used as attack vectors - such as
 * unique device identifiers(MAC address, OS version, etc.), nano timestamp, constants not related
 * to cipher specifications, etc.
 *
 * @author Francesco Jo(nimbusob@gmail.com)
 * @since 25 - May - 2018
 */
@SuppressLint("StaticFieldLeak")
object EncryptionRSA {
    /** All inputs are must be shorter than 2048 bits(256 bytes) */
    private const val KEY_LENGTH_BIT = 2048

    // Let's think about this problem in 2043
    private const val VALIDITY_YEARS = 25

    private const val KEY_PROVIDER_NAME = "AndroidKeyStore"
    private const val CIPHER_ALGORITHM =
        "${KeyProperties.KEY_ALGORITHM_RSA}/" +
                "${KeyProperties.BLOCK_MODE_ECB}/" +
                KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1

//    private lateinit var keyEntry: KeyStore.Entry
    private lateinit var publicKey: PublicKey
    private lateinit var privateKey: PrivateKey

    // Private only backing field
    @Suppress("ObjectPropertyName")
    private var _isSupported = false

    val isSupported: Boolean
        get() = _isSupported

    private lateinit var appContext: Context

    internal fun init(applicationContext: Context) {
        if (isSupported) {
            return
        }

        this.appContext = applicationContext
        val alias = "${appContext.packageName}.rsakeypairs"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply{
            load(null)
        }

        val result: Boolean
        result = if (keyStore.containsAlias(alias)) {
            true
        } else {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1 && initAndroidM(alias)) {
                true
            } else {
                initAndroidL(alias)
            }
        }

        this.publicKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            keyStore.getCertificate(alias).publicKey
        } else {
            val asymmetricKey = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            asymmetricKey.certificate.publicKey
        }
        this.privateKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            keyStore.getKey(alias, null) as PrivateKey
        } else {
            val asymmetricKey = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            asymmetricKey.privateKey
        }
        _isSupported = result
    }

    private fun initAndroidM(alias: String): Boolean {
        try {
            with(KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEY_PROVIDER_NAME)) {
                val spec = KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(KEY_LENGTH_BIT, F4))
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setDigests(KeyProperties.DIGEST_SHA512,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA256)
                    /*
                             * Setting true only permit the private key to be used if the user authenticated
                             * within the last five minutes.
                             */
                    .setUserAuthenticationRequired(false)
                    .build()
                initialize(spec)
                generateKeyPair()
            }
            return true
        } catch (e: GeneralSecurityException) {
            /*
             * Nonsense, but some devices manufactured by developing countries have actual problem
             * Consider using JCE substitutes like Spongy castle(Bouncy castle for android)
             */
            return false
        }
    }

    /**
     * Tested and verified working on Nexus 5s API Level 21, it is not guaranteed that this logic is valid on
     * all Android L devices.
     */
    private fun initAndroidL(alias: String): Boolean {
        try {
            with(KeyPairGenerator.getInstance("RSA", KEY_PROVIDER_NAME)) {
                val start = Calendar.getInstance(Locale.ENGLISH)
                val end = Calendar.getInstance(Locale.ENGLISH).apply { add(Calendar.YEAR, VALIDITY_YEARS) }
                val spec = KeyPairGeneratorSpec.Builder(appContext)
                    .setKeySize(KEY_LENGTH_BIT)
                    .setAlias(alias)
                    .setSubject(X500Principal("CN=francescojo.github.com, OU=Android dev, O=Francesco Jo, L=Chiyoda, ST=Tokyo, C=JP"))
                    .setSerialNumber(BigInteger.ONE)
                    .setStartDate(start.time)
                    .setEndDate(end.time)
                    .build()
                initialize(spec)
                generateKeyPair()
            }
            return true
        } catch (e: GeneralSecurityException) {
            return false
        }
    }

    /**
     * Beware that input must be shorter than 256 bytes. The length limit of plainText could be dramatically
     * shorter than 256 letters in certain character encoding, such as UTF-8.
     */
    fun encrypt(plainText: String): String {
        if (!_isSupported) {
            return plainText
        }

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, publicKey)
        }
        val bytes = plainText.toByteArray(Charsets.UTF_8)
        val encryptedBytes = cipher.doFinal(bytes)
        val base64EncryptedBytes = Base64.encode(encryptedBytes, Base64.DEFAULT)

        return String(base64EncryptedBytes)
    }

    fun decrypt(base64EncryptedCipherText: String): String {
        if (!_isSupported) {
            return base64EncryptedCipherText
        }

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM).apply{
            init(Cipher.DECRYPT_MODE, privateKey)
        }
        val base64EncryptedBytes = base64EncryptedCipherText.toByteArray(Charsets.UTF_8)
        val encryptedBytes = Base64.decode(base64EncryptedBytes, Base64.DEFAULT)
        val decryptedBytes = cipher.doFinal(encryptedBytes)

        return String(decryptedBytes)
    }

    fun getPublicKey():String{
        if (_isSupported) {
            return publickeyToStoring(publicKey)
        }else{
            return "Don't have public key"
        }
    }

    internal fun changeKey(applicationContext: Context) {
        this.appContext = applicationContext
        val alias = "${appContext.packageName}.rsakeypairs"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply{
            load(null)
        }

        keyStore.deleteEntry(alias)

        init(applicationContext)
    }

    fun stringToPublickey(keyString:String):PublicKey{
        val publicBytes = Base64.decode(keyString, Base64.DEFAULT)
        val keySpec = X509EncodedKeySpec(publicBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        return keyFactory.generatePublic(keySpec)
    }

    fun publickeyToStoring(pubKey: PublicKey):String{
        return Base64.encodeToString(pubKey.encoded, Base64.DEFAULT)
    }

    fun stringToPrivatekey(keyString:String):PrivateKey{
        val publicBytes = Base64.decode(keyString, Base64.DEFAULT)
        val keySpec = X509EncodedKeySpec(publicBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        return keyFactory.generatePrivate(keySpec)
    }

    fun privatekeyToStoring(priKey: PrivateKey):String{
        return Base64.encodeToString(priKey.encoded, Base64.DEFAULT)
    }

    fun encryptTest(plainText: String, pubKey: PublicKey): String {
        if (!_isSupported) {
            return plainText
        }

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, pubKey)
        }
        val bytes = plainText.toByteArray(Charsets.UTF_8)
        val encryptedBytes = cipher.doFinal(bytes)
        val base64EncryptedBytes = Base64.encode(encryptedBytes, Base64.DEFAULT)

        return String(base64EncryptedBytes)
    }

    fun decryptTest(base64EncryptedCipherText: String, priKey: PrivateKey): String {
        if (!_isSupported) {
            return base64EncryptedCipherText
        }

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM).apply{
            init(Cipher.DECRYPT_MODE, priKey)
        }
        val base64EncryptedBytes = base64EncryptedCipherText.toByteArray(Charsets.UTF_8)
        val encryptedBytes = Base64.decode(base64EncryptedBytes, Base64.DEFAULT)
        val decryptedBytes = cipher.doFinal(encryptedBytes)

        return String(decryptedBytes)
    }
}