package cn.jnu.hub.feature.utility

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 水电系统（pynhcx / CetSoftEEM）的 AES 加解密。
 *
 * 参数全部来自站点前端 all-classes.js 的 Ext.util.crypt 实现，原样复刻：
 *
 *   function stringToHex(g) {          // 每个字符 charCode.toString(16) 直接拼接
 *     var e = "";
 *     for (var d = 0; d < g.length; d++) {
 *       if (e == "") e = g.charCodeAt(d).toString(16)
 *       else e += g.charCodeAt(d).toString(16)
 *     }
 *     return e
 *   }
 *   function c(e, d) {                 // encrypt
 *     var g = stringToHex(d)
 *     key = CryptoJS.enc.Hex.parse(g)
 *     iv  = CryptoJS.enc.Hex.parse("1934577290ABCDEF1264147890ACAE45")
 *     return CryptoJS.AES.encrypt(e, key, {iv: iv}).toString()   // Base64
 *   }
 *   Ext.util.crypt.decode = a => Ext.util.crypt.AES.decrypt(a, EEM.AES.keyStr)
 *   EEM.AES.keyStr = "CetSoftEEMSysWeb"
 *
 * 注意两个易错点：
 * 1. key 不是直接把 "CetSoftEEMSysWeb" 当字节用，而是先 stringToHex 再 Hex.parse。
 * 2. toString(16) 不补零（charCode 小于 0x10 的字符只产生 1 位），
 *    这里用 Integer.toHexString 而非格式化补零才是等价实现。
 */
internal object UtilityCrypto {

    /** 前端硬编码的 AES 口令 */
    const val KEY_STR = "CetSoftEEMSysWeb"

    /** 前端硬编码的 IV（16 字节 hex） */
    private const val IV_HEX = "1934577290ABCDEF1264147890ACAE45"

    /**
     * 复刻 JS 的 stringToHex：每个 charCode 转 16 进制且**不补零**后拼接。
     * 例如 "Cet" -> "43" + "65" + "74" = "436574"
     */
    fun stringToHex(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            sb.append(Integer.toHexString(ch.code))
        }
        return sb.toString()
    }

    /** 由口令推出 CryptoJS.enc.Hex.parse 后的 key 字节 */
    private fun keyBytes(passphrase: String): ByteArray =
        hexToBytes(stringToHex(passphrase))

    private fun ivBytes(): ByteArray = hexToBytes(IV_HEX)

    private fun hexToBytes(hex: String): ByteArray {
        // Hex.parse 遇到奇数长度时，最后一个半字节按低位补齐（与 CryptoJS 行为一致）
        val out = ByteArray((hex.length + 1) / 2)
        var i = 0
        while (i < hex.length) {
            val hi = Character.digit(hex[i], 16).coerceAtLeast(0)
            val lo = if (i + 1 < hex.length) {
                Character.digit(hex[i + 1], 16).coerceAtLeast(0)
            } else {
                0
            }
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    /** AES-CBC + PKCS7 加密，输出 Base64（等价 CryptoJS.AES.encrypt(...).toString()） */
    fun encrypt(plainText: String, passphrase: String = KEY_STR): String {
        // JCE 的 PKCS5Padding 在 AES 下即 PKCS7（块长 16 字节）
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes(passphrase), "AES"),
            IvParameterSpec(ivBytes()),
        )
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /** AES-CBC + PKCS7 解密（等价 Ext.util.crypt.decode）；失败返回 null */
    fun decrypt(base64: String, passphrase: String = KEY_STR): String? {
        if (base64.isBlank()) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes(passphrase), "AES"),
                IvParameterSpec(ivBytes()),
            )
            val raw = Base64.decode(normalizeBase64(base64), Base64.DEFAULT)
            String(cipher.doFinal(raw), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Cookie 里的 Base64 经常被 URL 编码过（+ / = 被转义），也可能被拆成多段。
     * 这里依次：URLDecode -> 还原 + -> 去空白 -> 去引号。
     */
    private fun normalizeBase64(raw: String): String {
        var s = raw.trim()
        if (s.contains("%")) {
            s = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        }
        // Cookie 里可能把 + 原样存成空格（form-urlencoded 语义）
        s = s.replace(" ", "+")
        // 去掉换行/其它空白
        s = s.replace(WHITESPACE, "")
        // 去掉可能的双引号包裹
        s = s.trim('"')
        // 顺手吃掉 JS 里出现过的那种尾部换行残留
        s = s.replace("\n", "").replace("\r", "")
        return s
    }

    private val WHITESPACE = Regex("\\s")
}
