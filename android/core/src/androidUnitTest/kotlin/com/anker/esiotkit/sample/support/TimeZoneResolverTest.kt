package com.anker.esiotkit.sample.support

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bug #8695: timezone_set 属性 Flow 原始值全格式解析回归测试。
 * 覆盖三类格式，防止 [TimeZoneResolver.resolveTimezoneId] 再次丢失格式分支。
 */
class TimeZoneResolverTest {

  @Test
  fun legacy_p2p_raw_format_resolves_by_timeSn() {
    // "CST-8|1.1297" -> timeSn 1297 -> Asia/Shanghai（#8695 复现值）
    assertEquals("Asia/Shanghai", TimeZoneResolver.resolveTimezoneId("CST-8|1.1297"))
  }

  @Test
  fun legacy_p2p_raw_format_resolves_other_sn() {
    // "EAT-3|1.1002" -> timeSn 1002 -> Africa/Addis_Ababa
    assertEquals("Africa/Addis_Ababa", TimeZoneResolver.resolveTimezoneId("EAT-3|1.1002"))
  }

  @Test
  fun nvr_three_segment_format_takes_last_iana_segment() {
    // NVR 三段式 "GMT|1.SN|Region/City" -> 取最后一段 IANA
    assertEquals("Africa/Addis_Ababa", TimeZoneResolver.resolveTimezoneId("EAT-3|1.1002|Africa/Addis_Ababa"))
  }

  @Test
  fun two_segment_iana_suffix_takes_last_segment() {
    assertEquals("Asia/Shanghai", TimeZoneResolver.resolveTimezoneId("CST-8|Asia/Shanghai"))
  }

  @Test
  fun pure_iana_id_returns_itself() {
    assertEquals("Asia/Shanghai", TimeZoneResolver.resolveTimezoneId("Asia/Shanghai"))
  }

  @Test
  fun pure_gmt_string_matches_by_gmt() {
    // "GMT0" -> 按 timeZoneGMT 整串匹配 -> Africa/Abidjan（timeSn 1000, GMT0）
    assertEquals("Africa/Abidjan", TimeZoneResolver.resolveTimezoneId("GMT0"))
  }

  @Test
  fun empty_input_returns_empty() {
    assertEquals("", TimeZoneResolver.resolveTimezoneId(""))
  }

  @Test
  fun unknown_format_returns_raw_for_caller_fallback() {
    // 无法解析时原样返回，由调用方决定兜底（如 TimeZone.of 抛异常后回退 UTC）
    assertEquals("not_a_timezone", TimeZoneResolver.resolveTimezoneId("not_a_timezone"))
  }

  @Test
  fun unknown_sn_returns_raw_for_caller_fallback() {
    // 未知 timeSn 不抛异常，原样返回
    assertEquals("CST-8|1.999999", TimeZoneResolver.resolveTimezoneId("CST-8|1.999999"))
  }
}
