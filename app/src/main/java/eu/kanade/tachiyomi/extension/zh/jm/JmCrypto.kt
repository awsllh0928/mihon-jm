package eu.kanade.tachiyomi.extension.zh.jm

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object JmCrypto {
    
    fun hexEncode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append("0123456789abcdef"[b.toInt() shr 4 and 0xf])
            sb.append("0123456789abcdef"[b.toInt() and 0xf])
        }
        return sb.toString()
    }

    fun md5(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("MD5").digest(input)
    }

    fun encodeUtf8(str: String): ByteArray = str.toByteArray(Charsets.UTF_8)
    fun decodeUtf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    fun decodeBase64(str: String): ByteArray = Base64.decode(str, Base64.DEFAULT)

    fun decryptAesEcb(data: ByteArray, key: ByteArray): ByteArray {
        val aesKey = when {
            key.size == 16 || key.size == 24 || key.size == 32 -> key
            else -> md5(key).copyOf(16)
        }
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        return cipher.doFinal(data)
    }

    fun convertData(input: String, secret: String): String {
        val key = encodeUtf8(hexEncode(md5(encodeUtf8(secret))))
        val data = decodeBase64(input)
        val decrypted = decryptAesEcb(data, key)
        val res = decodeUtf8(decrypted)
        var start = 0
        while (start < res.length && res[start] != '{' && res[start] != '[') start++
        var end = res.length - 1
        while (end > start && res[end] != '}' && res[end] != ']') end--
        if (start > end) throw Exception("Cannot find JSON in decrypted data")
        return res.substring(start, end + 1)
    }

    fun generateToken(time: Long, jmAuthKey: String): String {
        return hexEncode(md5(encodeUtf8("${time}${jmAuthKey}")))
    }
}
