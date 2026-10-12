package com.anker.esiotkit.sample.demo.analyzer

import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.sample.demo.ai.IAiAnalysisService
import com.anker.esiotkit.sample.demo.data.IEventDataSource
import com.anker.esiotkit.sample.demo.model.EventMatchResult
import com.anker.esiotkit.sample.demo.model.MatchStatus
import com.anker.esiotkit.support.log.appLogger

internal object EventAnalysisSupport {

  suspend fun analyzeEvent(
    aiService: IAiAnalysisService,
    dataSource: IEventDataSource,
    event: MegaEvent,
  ): EventMatchResult {
    val sdkType = MatchRules.sdkTypeOf(event)
    return try {
      val thumbnail = dataSource.fetchThumbnailBase64(event)
      if (thumbnail.isNullOrEmpty()) {
        appLogger.w("EventAnalysisSupport/degrade-text: eventId=${event.megaEventId} thumbnail null")
        return degraded(event, sdkType)
      }
      val imageResult = aiService.analyzeImage(thumbnail)
      if (imageResult == null) {
        appLogger.w("EventAnalysisSupport/degrade-text: eventId=${event.megaEventId} ai null/timeout")
        return degraded(event, sdkType)
      }
      EventMatchResult(
        event = event,
        sdkType = sdkType,
        aiHasPerson = imageResult.hasPerson,
        aiDescription = imageResult.description,
        matchStatus = MatchRules.matchEvent(sdkType, imageResult.hasPerson),
      )
    } catch (e: Exception) {
      appLogger.e("EventAnalysisSupport/analyzeEvent/error: eventId=${event.megaEventId} ${e.message}")
      degraded(event, sdkType, "分析异常: ${e.message}")
    }
  }

  private fun degraded(event: MegaEvent, sdkType: String, note: String = "文本降级"): EventMatchResult {
    val text = event.stringExtra.ifBlank { sdkType }
    return EventMatchResult(
      event = event,
      sdkType = sdkType,
      aiHasPerson = false,
      aiDescription = "$note: $text",
      matchStatus = MatchStatus.UNKNOWN,
    )
  }
}
