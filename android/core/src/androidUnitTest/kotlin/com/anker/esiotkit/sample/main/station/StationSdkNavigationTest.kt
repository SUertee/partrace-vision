package com.anker.esiotkit.sample.main.station

import org.junit.Assert.*
import org.junit.Test

class StationSdkNavigationTest {
  private data class TestScreen(val id: String, val backend: Boolean = false, val key: String = id)

  private val home = TestScreen("home")

  @Test fun `SDK logout removes the SDK top and its historical screens`() {
    val stack = listOf(home, TestScreen("sdk_old"), TestScreen("sdk_current"))
    var revalidations = 0
    val result = stationStackAfterSdkLogout(home, stack, { it.backend }, {
      revalidations++
      it.copy(key = "revalidated")
    }, revalidateBackendSession = true)

    assertEquals(listOf(home), result)
    assertSame(home, result.single())
    assertEquals(0, revalidations)
  }

  @Test fun `SDK logout preserves only the exact current backend target without its old backstack`() {
    val oldBackend = TestScreen("evt_old", backend = true)
    val currentBackend = TestScreen("evt_current", backend = true)
    val result = stationStackAfterSdkLogout(home,
      listOf(home, TestScreen("sdk_old"), oldBackend, TestScreen("sdk_later"), currentBackend), { it.backend },
      { throw AssertionError("An unchanged backend session must not be revalidated") },
      revalidateBackendSession = false)

    assertEquals(listOf(home, currentBackend), result)
    assertSame(home, result.first())
    assertSame(currentBackend, result.last())
  }

  @Test fun `first SDK logout revalidates the current backend once while preserving its target ID`() {
    val currentBackend = TestScreen("evt_current", backend = true, key = "existing_session")
    val revalidated = mutableListOf<TestScreen>()
    val result = stationStackAfterSdkLogout(home, listOf(home, currentBackend), { it.backend }, {
      revalidated.add(it)
      it.copy(key = "fresh_session")
    }, revalidateBackendSession = true)

    assertEquals(listOf(currentBackend), revalidated)
    assertSame(currentBackend, revalidated.single())
    assertEquals(2, result.size)
    assertSame(home, result.first())
    assertEquals(currentBackend.id, result.last().id)
    assertTrue(result.last().backend)
    assertEquals("fresh_session", result.last().key)
    assertNotSame(currentBackend, result.last())
  }

  @Test fun `repeated SDK logout keeps the revalidated stack and target identity without another callback`() {
    val currentBackend = TestScreen("evt_current", backend = true)
    var revalidations = 0
    val first = stationStackAfterSdkLogout(home, listOf(home, TestScreen("sdk_old"), currentBackend),
      { it.backend }, {
        revalidations++
        it.copy(key = "fresh_session")
      }, revalidateBackendSession = true)
    val second = stationStackAfterSdkLogout(home, first, { it.backend }, {
      revalidations++
      throw AssertionError("Repeated SDK logout must retain the existing backend session")
    }, revalidateBackendSession = false)

    assertEquals(first, second)
    assertSame(first.first(), second.first())
    assertSame(first.last(), second.last())
    assertEquals(1, revalidations)
  }

  @Test fun `Home at the top yields only Home without reviving historical backend targets`() {
    val result = stationStackAfterSdkLogout(home,
      listOf(home, TestScreen("evt_old", backend = true), home), { it.backend },
      { throw AssertionError("Home must not be revalidated") }, revalidateBackendSession = true)

    assertEquals(listOf(home), result)
    assertSame(home, result.single())
  }

  @Test fun `an empty stack restores only Home`() {
    val result = stationStackAfterSdkLogout(home, emptyList(), { it.backend },
      { throw AssertionError("An empty stack must not be revalidated") }, revalidateBackendSession = true)

    assertEquals(listOf(home), result)
    assertSame(home, result.single())
  }

  @Test fun `SDK at the top does not expose a backend target underneath it`() {
    val hiddenBackend = TestScreen("evt_hidden", backend = true)
    val sdkTop = TestScreen("sdk_current")
    val result = stationStackAfterSdkLogout(home, listOf(home, hiddenBackend, sdkTop), { it.backend },
      { throw AssertionError("A hidden backend target must not be revalidated") },
      revalidateBackendSession = true)

    assertEquals(listOf(home), result)
    assertFalse(result.contains(hiddenBackend))
  }
}
