package com.anker.esiotkit.sample.demo.analyzer

import com.anker.esiotkit.core.entities.event.DetectionType
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.sample.demo.model.MatchStatus

/** SDK 检测类型 vs AI 判断 的匹配规则（设计文档 §3.6） */
object MatchRules {

  private val PERSON_TYPES = setOf("BODY", "FACE", "MOTION")

  fun matchEvent(sdkType: String, aiHasPerson: Boolean): MatchStatus = when {
    sdkType in PERSON_TYPES && aiHasPerson -> MatchStatus.CONFIRMED
    sdkType in PERSON_TYPES && !aiHasPerson -> MatchStatus.FALSE_ALARM
    else -> MatchStatus.CONFIRMED
  }

  fun sdkTypeOf(event: MegaEvent): String {
    val detectionType = event.thumbnail?.detectionType ?: return "UNKNOWN"
    return detectionTypeToString(detectionType)
  }

  fun detectionTypeToString(detectionType: Int): String = when (detectionType) {
    DetectionType.HUMAN.value, DetectionType.FACE.value -> "BODY"
    DetectionType.CAR.value -> "VEHICLE"
    DetectionType.CRY.value -> "CRYING"
    DetectionType.PACKAGE_PUT.value, DetectionType.PACKAGE_TAKE.value -> "PACKAGE"
    DetectionType.PET.value -> "PET"
    DetectionType.SOUND.value -> "SOUND"
    DetectionType.MOTION.value -> "MOTION"
    DetectionType.RING.value, DetectionType.ANSWERED_RING.value, DetectionType.MISSED_RING.value -> "RING"
    DetectionType.ALERT.value -> "ALERT"
    else -> "UNKNOWN($detectionType)"
  }
}
