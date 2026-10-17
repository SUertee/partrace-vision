package com.anker.esiotkit.sample.ui.composable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissState
import androidx.compose.material.DismissValue
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.FixedThreshold
import androidx.compose.material.ResistanceConfig
import androidx.compose.material.SwipeableDefaults
import androidx.compose.material.ThresholdConfig
import androidx.compose.material.rememberDismissState
import androidx.compose.material.swipeable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.Navigator
import com.anker.esiotkit.support.log.appLogger
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

expect val shouldUseSwipeBack: Boolean // on iOS, this would be true and on Android/Desktop, false

/**
 * @param slideInHorizontally a lambda that takes the full width of the
 * content in pixels and returns the initial offset for the slide-in prev entry,
 * by default it returns -fullWidth/4.
 * For the best impression, lambda should return the save value as [NavTransition.pauseTransition]
 * @param spaceToSwipe width of the swipe space from the left side of screen.
 * Can be set to [Int.MAX_VALUE].dp to enable full-scene swipe
 * @param swipeThreshold amount of offset to perform back navigation
 * @param shadowColor color of the shadow. Alpha channel is additionally multiplied
 * by swipe progress. Use [Color.Transparent] to disable shadow
 * */
@OptIn(ExperimentalMaterialApi::class)
class SwipeProperties(
  val slideInHorizontally: (fullWidth: Int) -> Int = { -it / 4 },
  val spaceToSwipe: Dp = 10.dp,
  val swipeThreshold: ThresholdConfig = FixedThreshold(56.dp),
  val shadowColor: Color = Color.Black.copy(alpha = .25f),
  val swipeAnimSpec: AnimationSpec<Float> = tween(),
)

@Composable
@OptIn(ExperimentalMaterialApi::class)
internal fun VoyagerSwipeBackContent(navigator: Navigator) {
  val exampleSwipeProperties = remember { SwipeProperties() }

  val currentSceneEntry = navigator.lastItem
  val prevSceneEntry = remember(navigator.items) { navigator.items.getOrNull(navigator.size - 2) }

  var prevWasSwiped by remember {
    mutableStateOf(false)
  }

  LaunchedEffect(currentSceneEntry) {
    prevWasSwiped = false
  }

  val dismissState = key(currentSceneEntry) {
    rememberDismissState()
  }

  LaunchedEffect(dismissState.isDismissed(DismissDirection.StartToEnd)) {
    if (dismissState.isDismissed(DismissDirection.StartToEnd)) {
      prevWasSwiped = true
      navigator.pop()
    }
  }

  val showPrev by remember(dismissState) {
    derivedStateOf {
      dismissState.offset.value > 0f
    }
  }

  val visibleItems = remember(currentSceneEntry, prevSceneEntry, showPrev) {
    if (showPrev) {
      listOfNotNull(currentSceneEntry, prevSceneEntry)
    } else {
      listOfNotNull(currentSceneEntry)
    }
  }
  appLogger.i("SwipeSupport/VoyagerSwipeBackContent/currentSceneEntry:$currentSceneEntry prevSceneEntry:$prevSceneEntry showPrev:$showPrev")
  appLogger.i("SwipeSupport/VoyagerSwipeBackContent/visibleItems:$visibleItems")

  val canGoBack = remember(navigator.size) { navigator.size > 1 }

  val animationSpec = remember {
    spring(
      stiffness = Spring.StiffnessMediumLow,
      visibilityThreshold = IntOffset.VisibilityThreshold
    )
  }

  // display visible items using SwipeItem
  visibleItems.forEachIndexed { index, backStackEntry ->
    val isPrev = remember(index, visibleItems.size) {
      index == 1 && visibleItems.size > 1
    }
    AnimatedContent(
      backStackEntry,
      transitionSpec = {
        if (prevWasSwiped) {
          EnterTransition.None togetherWith ExitTransition.None
        } else {
          val (initialOffset, targetOffset) = when (navigator.lastEvent) {
            StackEvent.Pop -> ({ size: Int -> -size }) to ({ size: Int -> size })
            else -> ({ size: Int -> size }) to ({ size: Int -> -size })
          }

          slideInHorizontally(animationSpec, initialOffset) togetherWith
          slideOutHorizontally(animationSpec, targetOffset)
        }
      },
      modifier = Modifier.zIndex(
        if (isPrev) {
          0f
        } else {
          0.99f
        },
      ),
    ) { screen ->
      SwipeItem(
        dismissState = dismissState,
        swipeProperties = exampleSwipeProperties,//actualSwipeProperties,
        isPrev = isPrev,
        isLast = !canGoBack,
      ) {
        navigator.saveableState("swipe", screen) {
          screen.Content()
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun SwipeItem(
  dismissState: DismissState,
  swipeProperties: SwipeProperties,
  isPrev: Boolean,
  isLast: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  CustomSwipeToDismiss(
    state = if (isPrev) rememberDismissState() else dismissState,
    spaceToSwipe = swipeProperties.spaceToSwipe,
    enabled = !isLast,
    dismissThreshold = swipeProperties.swipeThreshold,
    modifier = modifier,
  ) {
    Box(
      modifier = Modifier
        .takeIf { isPrev }
        ?.graphicsLayer {
          translationX =
            swipeProperties.slideInHorizontally(size.width.toInt())
              .toFloat() -
            swipeProperties.slideInHorizontally(
              dismissState.offset.value.absoluteValue.toInt(),
            )
        }?.drawWithContent {
          drawContent()
          drawRect(
            swipeProperties.shadowColor,
            alpha = (1f - dismissState.progress.fraction) *
            swipeProperties.shadowColor.alpha,
          )
        }?.pointerInput(0) {
          // prev entry should not be interactive until fully appeared
        } ?: Modifier,
    ) {
      content.invoke()
    }
  }
}

@Composable
@ExperimentalMaterialApi
private fun CustomSwipeToDismiss(
  state: DismissState,
  enabled: Boolean = true,
  spaceToSwipe: Dp = 10.dp,
  modifier: Modifier = Modifier,
  dismissThreshold: ThresholdConfig,
  dismissContent: @Composable () -> Unit,
) = BoxWithConstraints(modifier) {
  val width = constraints.maxWidth.toFloat()
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

  val anchors = mutableMapOf(
    0f to DismissValue.Default,
    width to DismissValue.DismissedToEnd,
  )

  val shift = with(LocalDensity.current) {
    remember(this, width, spaceToSwipe) {
      (-width + spaceToSwipe.toPx().coerceIn(0f, width)).roundToInt()
    }
  }
  Box(
    modifier = Modifier
      .offset { IntOffset(x = shift, 0) }
      .swipeable(
        state = state,
        anchors = anchors,
        thresholds = { _, _ -> dismissThreshold },
        orientation = Orientation.Horizontal,
        enabled = enabled && state.currentValue == DismissValue.Default,
        reverseDirection = isRtl,
        resistance = ResistanceConfig(
          basis = width,
          factorAtMin = SwipeableDefaults.StiffResistanceFactor,
          factorAtMax = SwipeableDefaults.StandardResistanceFactor,
        ),
      )
      .offset { IntOffset(x = -shift, 0) }
      .graphicsLayer { translationX = state.offset.value },

    ) {
    dismissContent()
  }
}