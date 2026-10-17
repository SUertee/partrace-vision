package com.anker.esiotkit.sample.main.mega.live_video

import org.junit.Test
import org.junit.Assert.*

/**
 * 验证 MegaLiveStreamViewModel companion object 中的 SN prefix 常量
 *
 * Kotlin private const val 编译后为 JVM 静态字段，挂在外部类上（非 Companion 类）。
 * 通过反射外部类静态字段的方式读取。
 */
class MegaLiveStreamViewModelPnTest {

    private fun getStaticConstant(fieldName: String): String? {
        return try {
            val clazz = MegaLiveStreamViewModel::class.java
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(null) as? String
        } catch (e: NoSuchFieldException) {
            // Kotlin const val 可能编译为内部类字段，尝试 Companion
            try {
                val companionClass = Class.forName(
                    "${MegaLiveStreamViewModel::class.java.name}\$Companion"
                )
                val field = companionClass.getDeclaredField(fieldName)
                field.isAccessible = true
                val companion = MegaLiveStreamViewModel::class.java
                    .getDeclaredField("Companion")
                    .also { it.isAccessible = true }
                    .get(null)
                field.get(companion) as? String
            } catch (e2: Exception) {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    @Test
    fun `SN_PREFIX_8417 常量存在且值为 T8417`() {
        val value = getStaticConstant("SN_PREFIX_8417")
        assertNotNull("SN_PREFIX_8417 常量不存在，请检查 MegaLiveStreamViewModel companion object", value)
        assertEquals("T8417", value)
    }

    @Test
    fun `SN_PREFIX_8N00 常量存在且值为 T8N00`() {
        val value = getStaticConstant("SN_PREFIX_8N00")
        assertNotNull("SN_PREFIX_8N00 常量不存在，请检查 MegaLiveStreamViewModel companion object", value)
        assertEquals("T8N00", value)
    }
}
