package com.anker.esiotkit.sample.main.station

/** SDK logout never grants access to a backend target; its fresh screen validates that session. */
internal fun <T> stationStackAfterSdkLogout(
  home: T,
  stack: List<T>,
  isBackendTarget: (T) -> Boolean,
  revalidateBackendTarget: (T) -> T,
  revalidateBackendSession: Boolean,
): List<T> {
  val current = stack.lastOrNull()
  return if (current != null && isBackendTarget(current)) {
    listOf(home, if (revalidateBackendSession) revalidateBackendTarget(current) else current)
  } else listOf(home)
}
