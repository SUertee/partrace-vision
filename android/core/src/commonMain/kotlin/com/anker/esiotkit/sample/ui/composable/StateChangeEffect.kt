package com.anker.esiotkit.sample.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * @version 2024/11/26
 */
@Composable
@NonRestartableComposable
fun StateChangeEffectIgnoringInitial(
  key1: Any?,
  canRunEffect: () -> Boolean = { true },
  effect: () -> Unit
) {
  var firstRemember by remember { mutableStateOf(true) }
  remember(key1) {
    StateChangeEffectImpl(
      remembered = {
        firstRemember = false
      },
      canRunEffect = canRunEffect,
      effect = effect,
      canRun = !firstRemember
    )
  }
}

@Composable
@NonRestartableComposable
fun StateChangeEffect(
  key1: Any?,
  canRunEffect: () -> Boolean = { true },
  effect: () -> Unit
) {
  remember(key1) { StateChangeEffectImpl(
    canRunEffect = canRunEffect,
    effect = effect,
  ) }
}

@Composable
@NonRestartableComposable
fun StateChangeEffect(
  key1: Any?,
  key2: Any?,
  canRunEffect: () -> Boolean = { true },
  effect: () -> Unit
) {
  remember(key1, key2) {
    StateChangeEffectImpl(
      canRunEffect = canRunEffect,
      effect = effect,
    )
  }
}

private class StateChangeEffectImpl(
  private val remembered: () -> Unit = {},
  private val canRunEffect: () -> Boolean,
  private val effect: () -> Unit,
  private val canRun: Boolean = true,
) : RememberObserver {
  override fun onRemembered() {
    remembered()

    // Remember will always be called once, but state change often only wants to get called when something changed
    if(canRun && canRunEffect()) {
      effect()
    }
  }

  override fun onForgotten() {
    // Nothing to do, we only need onRemembered to run
  }

  override fun onAbandoned() {
    // Nothing to do as [onRemembered] was not called.
  }
}

class EasingInterpolator(val ease: Ease) {
  val easingProvider = EasingProvider.get(ease)

  fun getInterpolation(input: Float): Float {
    return easingProvider(input)
  }
}

/**
 * The Easing class provides a collection of ease functions. It does not use the standard 4 param
 * ease signature. Instead it uses a single param which indicates the current linear ratio (0 to 1) of the tween.
 */
enum class Ease {
  LINEAR,
  QUAD_IN,
  QUAD_OUT,
  QUAD_IN_OUT,
  CUBIC_IN,
  CUBIC_OUT,
  CUBIC_IN_OUT,
  QUART_IN,
  QUART_OUT,
  QUART_IN_OUT,
  QUINT_IN,
  QUINT_OUT,
  QUINT_IN_OUT,
  SINE_IN,
  SINE_OUT,
  SINE_IN_OUT,
  BACK_IN,
  BACK_OUT,
  BACK_IN_OUT,
  CIRC_IN,
  CIRC_OUT,
  CIRC_IN_OUT
}

typealias EaseFunc = (Float) -> Float

/**
 * The Easing class provides a collection of ease functions. It does not use the standard 4 param
 * ease signature. Instead it uses a single param which indicates the current linear ratio (0 to 1) of the tween.
 */
internal object EasingProvider {
  operator fun get(ease: Ease?): EaseFunc {
    return when(ease) {
      Ease.LINEAR -> { { it } }
      Ease.QUAD_IN -> { { getPowIn(it, 2.0) } }
      Ease.QUAD_OUT -> { { getPowOut(it, 2.0) } }
      Ease.QUAD_IN_OUT -> { { getPowInOut(it, 2.0) } }
      Ease.CUBIC_IN -> { { getPowIn(it, 3.0) } }
      Ease.CUBIC_OUT -> { { getPowOut(it, 3.0) } }
      Ease.CUBIC_IN_OUT -> { { getPowInOut(it, 3.0) } }
      Ease.QUART_IN -> { { getPowIn(it, 4.0) } }
      Ease.QUART_OUT -> { { getPowOut(it, 4.0) } }
      Ease.QUART_IN_OUT -> { { getPowInOut(it, 4.0) } }
      Ease.QUINT_IN -> { { getPowIn(it, 5.0) } }
      Ease.QUINT_OUT -> { { getPowOut(it, 5.0) } }
      Ease.QUINT_IN_OUT -> { { getPowInOut(it, 5.0) } }
      Ease.SINE_IN -> { { (1f - cos(it * PI / 2f)).toFloat() } }
      Ease.SINE_OUT -> { { sin(it * PI / 2f).toFloat() } }
      Ease.SINE_IN_OUT -> { { (-0.5f * (cos(PI * it) - 1f)).toFloat() } }
      Ease.BACK_IN -> { { (it * it * ((1.7 + 1f) * it - 1.7)).toFloat() } }
      Ease.BACK_OUT -> { {
        val elapsedTimeRate = it - 1
        (elapsedTimeRate * elapsedTimeRate * ((1.7 + 1f) * elapsedTimeRate + 1.7) + 1f).toFloat()
      } }
      Ease.BACK_IN_OUT -> { { getBackInOut(it, 1.7f) } }
      Ease.CIRC_IN -> { { -(sqrt((1f - it * it).toDouble()) - 1).toFloat() } }
      Ease.CIRC_OUT -> { {
        val elapsedTimeRate = it - 1
        sqrt((1f - elapsedTimeRate * elapsedTimeRate).toDouble()).toFloat()
      } }
      Ease.CIRC_IN_OUT -> {
        {
          var elapsedTimeRate = it * 2f
          if (elapsedTimeRate < 1f) {
            (-0.5f * (sqrt(1f - elapsedTimeRate * elapsedTimeRate) - 1f))
          } else {
            elapsedTimeRate -= 2f
            (0.5f * (sqrt(1f - elapsedTimeRate * elapsedTimeRate) + 1f));
          }
        }
      }
      else -> { { it } }
    }
  }

  /**
   * @param elapsedTimeRate Elapsed displayDateTime / Total displayDateTime
   * @param pow             pow The exponent to use (ex. 3 would return a cubic ease).
   * @return easedValue
   */
  private fun getPowIn(elapsedTimeRate: Float, pow: Double): Float {
    return elapsedTimeRate.toDouble().pow(pow).toFloat()
  }

  /**
   * @param elapsedTimeRate Elapsed displayDateTime / Total displayDateTime
   * @param pow             pow The exponent to use (ex. 3 would return a cubic ease).
   * @return easedValue
   */
  private fun getPowOut(elapsedTimeRate: Float, pow: Double): Float {
    return (1f - (1 - elapsedTimeRate).toDouble().pow(pow)).toFloat()
  }

  /**
   * @param elapsedTimeRate Elapsed displayDateTime / Total displayDateTime
   * @param pow             pow The exponent to use (ex. 3 would return a cubic ease).
   * @return easedValue
   */
  private fun getPowInOut(elapsedTimeRate: Float, pow: Double): Float {
    var elapsedTimeRate = elapsedTimeRate
    return if(2.let { elapsedTimeRate *= it; elapsedTimeRate } < 1) {
      (0.5 * elapsedTimeRate.toDouble().pow(pow)).toFloat()
    } else (1 - 0.5 * abs((2 - elapsedTimeRate).toDouble().pow(pow))).toFloat()
  }

  /**
   * @param elapsedTimeRate Elapsed displayDateTime / Total displayDateTime
   * @param amount          amount The strength of the ease.
   * @return easedValue
   */
  private fun getBackInOut(elapsedTimeRate: Float, amount: Float): Float {
    var elapsedTimeRate = elapsedTimeRate
    var amount = amount
    amount *= 1.525.toFloat()
    return if(2.let { elapsedTimeRate *= it; elapsedTimeRate } < 1) {
      (0.5 * (elapsedTimeRate * elapsedTimeRate * ((amount + 1) * elapsedTimeRate - amount))).toFloat()
    } else (0.5 * (2.let { elapsedTimeRate -= it; elapsedTimeRate } * elapsedTimeRate * ((amount + 1) * elapsedTimeRate + amount) + 2)).toFloat()
  }
}