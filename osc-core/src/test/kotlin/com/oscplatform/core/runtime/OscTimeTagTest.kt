package com.oscplatform.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [OscTimeTag] ユーティリティの単体テスト。
 *
 * IMMEDIATE 定数および Java エポックミリ秒 → NTP 64-bit timetag 変換を検証する。
 */
class OscTimeTagTest {

  /** [OscTimeTag.IMMEDIATE] が OSC 仕様に従い 1 であることを検証する。 */
  @Test
  fun oscTimeTagImmediateIsOne() {
    assertEquals(1L, OscTimeTag.IMMEDIATE)
  }

  /** Unix エポック (0ms) を変換すると NTP 秒フィールドが 2_208_988_800 になることを検証する。 */
  @Test
  fun oscTimeTagFromEpochMillisProducesHigherBitForSeconds() {
    // 0ms (Unix epoch) → NTP秒フィールドは 2_208_988_800
    val tag = OscTimeTag.fromEpochMillis(0L)
    val seconds = tag ushr 32
    assertEquals(2_208_988_800L, seconds)
  }

  /** 500ms のフラクション部が NTP 形式で約 0x8000_0000 になることを検証する。 */
  @Test
  fun oscTimeTagFromEpochMillisIncorporatesFractions() {
    // 500ms → fraction ≈ 0x8000_0000
    val tag = OscTimeTag.fromEpochMillis(500L)
    val fraction = tag and 0xFFFF_FFFFL
    // 500ms / 1000ms * 2^32 ≈ 2_147_483_648 (0x80000000)
    assertTrue(fraction in 0x7FFF_0000L..0x8001_0000L, "fraction=$fraction")
  }
}
