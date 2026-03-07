package com.oscplatform.core.runtime

/**
 * OSC timetag ユーティリティ。
 *
 * OSC 1.0 仕様では timetag は NTP 64-bit timestamp (seconds since 1900-01-01 UTC)。
 * 特殊値 `1` は「即時送信 (immediate)」を意味する。
 */
object OscTimeTag {
    /** 即時送信を示す OSC 特殊値 */
    const val IMMEDIATE: Long = 1L

    /**
     * Java エポック (1970-01-01 UTC) からのミリ秒を OSC NTP timetag に変換する。
     *
     * NTP エポックは 1900-01-01 UTC。その差は 70年分の秒数。
     */
    fun fromEpochMillis(epochMillis: Long): Long {
        // NTP エポックと Unix エポックの差 (秒)
        val ntpEpochOffset = 2_208_988_800L
        val seconds = epochMillis / 1_000 + ntpEpochOffset
        val fraction = (epochMillis % 1_000) * (0x1_0000_0000L / 1_000)
        return (seconds shl 32) or (fraction and 0xFFFF_FFFFL)
    }
}
