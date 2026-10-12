package com.anker.esiotkit.sample.base

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.support.JobCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * @version 2024/6/11
 */
typealias BaseViewModel = BaseScreenModel

abstract class BaseScreenModel : ScreenModel, JobCoroutineScope() {
  override val coroutineContext: CoroutineContext
    get() = screenModelScope.coroutineContext
  val viewModelScope get() = screenModelScope
  var loading by mutableStateOf(false)
  var error by mutableStateOf<String?>(null)

  fun clearError() {
    error = null
  }

  protected suspend fun withLoading(block: suspend () -> Unit) {
    loading = true
    error = null
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      error = e.message ?: "Unknown error"
    } finally {
      loading = false
    }
  }

  fun launchWithLoading(
    name: String = "",
    context: CoroutineContext = coroutineContext,
    block: suspend () -> Unit
  ) {
    launch(
      name = name,
      context = context
    ) {
      loading = true
      error = null
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        error = e.message ?: "Unknown error"
      } finally {
        loading = false
      }
    }
  }
}