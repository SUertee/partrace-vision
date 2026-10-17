package com.anker.esiotkit.sample.demo.progress

import com.anker.esiotkit.support.platform.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object DemoProgress {

  enum class Status { RUNNING, SUCCESS, FAILED }

  data class Step(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: Status = Status.RUNNING,
    val startedAtMs: Long = currentTimeMillis(),
    val elapsedMs: Long? = null,
  )

  private val _steps = MutableStateFlow<List<Step>>(emptyList())
  val steps: StateFlow<List<Step>> = _steps.asStateFlow()

  fun reset() {
    _steps.value = emptyList()
  }

  fun start(id: String, title: String, detail: String = "") {
    _steps.update { list -> list.upsert(Step(id = id, title = title, detail = detail)) }
  }

  fun update(id: String, detail: String) {
    _steps.update { list -> list.map { if (it.id == id) it.copy(detail = detail) else it } }
  }

  fun success(id: String, detail: String? = null) {
    _steps.update { list ->
      val current = list.find { it.id == id } ?: Step(id = id, title = id)
      list.upsert(current.finish(Status.SUCCESS, detail))
    }
  }

  fun fail(id: String, detail: String) {
    _steps.update { list ->
      val current = list.find { it.id == id } ?: Step(id = id, title = id)
      list.upsert(current.finish(Status.FAILED, detail))
    }
  }

  fun failRunning(detail: String) {
    _steps.update { list ->
      list.map { step -> if (step.status == Status.RUNNING) step.finish(Status.FAILED, detail) else step }
    }
  }

  private fun Step.finish(status: Status, detail: String?): Step =
    copy(detail = detail ?: this.detail, status = status, elapsedMs = currentTimeMillis() - startedAtMs)

  private fun List<Step>.upsert(step: Step): List<Step> =
    if (any { it.id == step.id }) map { if (it.id == step.id) step else it } else this + step
}
