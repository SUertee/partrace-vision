package com.anker.esiotkit.sample.utils

import com.anker.esiotkit.core.constants.SnConstants

/**
 * @version 2023/9/19
 */
fun String.isStation1(): Boolean = this.startsWith(SnConstants.STATION)
fun String.isStationAI(): Boolean = this.startsWith(SnConstants.STATION_AI)
fun String.isStation2(): Boolean = this.startsWith(SnConstants.STATION_2)
fun String.isStation3(): Boolean = this.startsWith(SnConstants.STATION_3)
fun String.isSolo8134(): Boolean = this.startsWith(SnConstants.BATTERY_SOLO_CAM_8134)
fun String.isSolo8124(): Boolean = this.startsWith(SnConstants.BATTERY_SOLO_CAM_SPOTLIGHT_SOLAR_8124)
fun String.isStationS(): Boolean = this.startsWith(SnConstants.STATION_S)
fun String.isIndoor8414(): Boolean = this.startsWith(SnConstants.INDOOR_CAMERA_COSTDOWN_T8414)
fun String.isIndoor8W11(): Boolean = this.startsWith("T8W11")
fun String.is8210(): Boolean = this.startsWith(SnConstants.BATTERY_DOORBELL_T8210)
fun String.is8200(): Boolean = this.startsWith(SnConstants.DOORBELL_2K_T8200)

fun String.isStation(): Boolean {
  return startsWith(SnConstants.STATION_2)
  || startsWith(SnConstants.STATION_3)
  || startsWith(SnConstants.STATION)
  || startsWith(SnConstants.STATION_AI)
  || startsWith(SnConstants.STATION_S)
  || startsWith(SnConstants.STATION_8021)
}