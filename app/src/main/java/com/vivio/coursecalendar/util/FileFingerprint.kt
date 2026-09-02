package com.vivio.coursecalendar.util

import java.security.MessageDigest

/** 文件指纹：用于识别同一文件重复导入（交接包《02》选择文件阶段）。 */
object FileFingerprint {
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
