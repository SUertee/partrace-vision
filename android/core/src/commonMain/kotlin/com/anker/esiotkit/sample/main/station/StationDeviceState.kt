package com.anker.esiotkit.sample.main.station

/** UI data projected from the currently authenticated SDK account. */
data class StationDeviceCard(
  val sn: String,
  val name: String,
  val online: Boolean?,
  val isStation: Boolean
)

data class StationDeviceState(
  val devices: List<StationDeviceCard> = emptyList(),
  val loading: Boolean = false,
  val needsLogin: Boolean = false,
  val errorMessage: String? = null
)
