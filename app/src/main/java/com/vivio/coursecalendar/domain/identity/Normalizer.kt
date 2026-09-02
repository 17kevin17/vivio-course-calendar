package com.vivio.coursecalendar.domain.identity

import java.text.Normalizer

/**
 * 规范化组件（交接包《03》第四节）：
 * - Unicode NFKC 规范化；
 * - 去除首尾空格并合并连续空白；
 * - 英文字母统一大小写；
 * - 不对人名、楼名做模糊拼写纠正。
 *
 * 规范化规则固定为 identityVersion=1，算法升级时可迁移。
 */
object Normalizer {

    const val IDENTITY_VERSION = 1

    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return nfkc.trim().replace(Regex("\\s+"), " ").lowercase()
    }

    /** 紧凑形式（去空格）用于身份拼接，保证一致性。 */
    fun compact(text: String?): String = normalize(text).replace(" ", "")
}
