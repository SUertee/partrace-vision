package com.anker.esiotkit.sample.main.mega.event

import kotlin.time.TimeSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material3.Icon
import com.anker.esiotkit.sample.view.DevicePlaceholderIcon
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.PullRefreshState
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.anker.esiotkit.core.entities.event.DetectionType
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.domain.model.MegaPicture
import com.anker.esiotkit.sample.support.rememberEventImageRequest
import com.anker.esiotkit.sample.ui.component.ConfirmDialog
import com.anker.esiotkit.support.ktx.msToDurationStringWithoutHour
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.log.eventLogger

/**
 * @version 2024/9/23
 */
@Composable
fun EventList(
  refreshing: Boolean,
  refreshState: PullRefreshState,
  modifier: Modifier = Modifier,
  eventList: List<MegaEvent>,
  onClick: (MegaEvent) -> Unit,
  onDeleteEvent: (MegaEvent) -> Unit,
  batchDownloadMode: Boolean = false,
  selectedEvents: List<MegaEvent> = emptyList(),
  onToggleSelect: (MegaEvent) -> Unit = {},
  batchDeleteMode: Boolean = false,
  selectedDeleteEvents: List<MegaEvent> = emptyList(),
  onToggleSelectDelete: (MegaEvent) -> Unit = {},
) {
  Box(
    modifier = modifier.padding(horizontal = 16.dp).fillMaxSize()
      .pullRefresh(refreshState),
    contentAlignment = Alignment.TopCenter
  ) {
    eventLogger.i("EventListComponent/EventList/size:${eventList.size} batchDownloadMode=$batchDownloadMode batchDeleteMode=$batchDeleteMode")
    LazyColumn(
      modifier = modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = 16.dp)
    ) {
      items(eventList, key = { event -> event.megaEventId }) { event ->
        EventItem(
          event = event,
          onClick = { onClick(event) },
          onDeleteEvent = { onDeleteEvent(event) },
          batchDownloadMode = batchDownloadMode,
          isSelected = selectedEvents.any { it.megaEventId == event.megaEventId },
          onToggleSelect = {
            appLogger.i("EVT-BATCH toggle select: ${event.megaEventId}, selected=${!selectedEvents.any { it.megaEventId == event.megaEventId }}")
            onToggleSelect(event)
          },
          batchDeleteMode = batchDeleteMode,
          isSelectedForDelete = selectedDeleteEvents.any { it.megaEventId == event.megaEventId },
          onToggleSelectDelete = {
            appLogger.i("EVT-DELETE toggle select: ${event.megaEventId}, selected=${!selectedDeleteEvents.any { it.megaEventId == event.megaEventId }}")
            onToggleSelectDelete(event)
          },
        )
      }
    }
    PullRefreshIndicator(refreshing, refreshState, Modifier.align(Alignment.TopCenter))
  }
}

@Composable
private fun EventItem(
  modifier: Modifier = Modifier,
  event: MegaEvent,
  onClick: () -> Unit,
  onDeleteEvent: () -> Unit,
  batchDownloadMode: Boolean = false,
  isSelected: Boolean = false,
  onToggleSelect: () -> Unit = {},
  batchDeleteMode: Boolean = false,
  isSelectedForDelete: Boolean = false,
  onToggleSelectDelete: () -> Unit = {},
) {
  val renderStart = remember { TimeSource.Monotonic.markNow() }
  val isFirstComposition = remember { mutableStateOf(true) }

  if (isFirstComposition.value) {
    eventLogger.i("[EVENT-RENDER] first composition: eventId=${event.megaEventId}, deviceName=${event.deviceName}")
    isFirstComposition.value = false
  }

  var showDeleteDialog by remember { mutableStateOf(false) }

  if (showDeleteDialog) {
    ConfirmDialog(
      title = "Delete Event",
      description = "Are you sure you want to delete this event video?",
      onConfirm = {
        showDeleteDialog = false
        onDeleteEvent()
      },
      onCancel = { showDeleteDialog = false }
    )
  }

  val isBatchMode = batchDownloadMode || batchDeleteMode
  val itemClick: () -> Unit = when {
    batchDownloadMode -> { { onToggleSelect() } }
    batchDeleteMode -> { { onToggleSelectDelete() } }
    else -> onClick
  }

  Card(
    modifier
      .padding(top = 8.dp)
      .fillMaxWidth()
      .then(if (isBatchMode) Modifier.clickable { itemClick() } else Modifier)
  ) {
    if (isBatchMode) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp)
      ) {
        Checkbox(
          checked = if (batchDownloadMode) isSelected else isSelectedForDelete,
          onCheckedChange = { itemClick() },
          modifier = Modifier.align(Alignment.CenterVertically)
        )
        EventItemContent(event, Modifier.weight(1f))
      }
    } else {
      EventDeleteWrapper(
        onClick = itemClick,
        onDeleteRequest = { showDeleteDialog = true }
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
        ) {
          EventItemContent(event, Modifier.weight(1f))
        }
      }
    }
  }

  val renderDuration = renderStart.elapsedNow().inWholeMilliseconds
  eventLogger.i("[EVENT-RENDER] eventId=${event.megaEventId}, duration=${renderDuration}ms")
}

@Composable
private fun EventItemContent(event: MegaEvent, contentModifier: Modifier = Modifier) {
  Box(
    modifier = Modifier
      .size(width = 104.dp, height = 72.dp)
      .padding(end = 12.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(Color(0xFFF5F5F5)),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      imageVector = DevicePlaceholderIcon,
      contentDescription = null,
      tint = Color.Unspecified,
      modifier = Modifier.size(32.dp)
    )
    AsyncImage(
      model = rememberEventImageRequest(event.thumbnail),
      contentDescription = null,
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Crop,
      onError = { error ->
        appLogger.e("[IMG-CACHE]", "thumbnail decode error: ${event.megaEventId}, error=${error.result.throwable?.message}")
      },
      onSuccess = {
        appLogger.i("[IMG-CACHE] thumbnail displayed: ${event.megaEventId}")
      }
    )
  }
  Column(
    modifier = contentModifier
      .padding(end = 16.dp)
  ) {
    Text(
      text = event.deviceName,
      fontSize = 18.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    LaunchedEffect(Unit) {
      appLogger.d("EventItem: ${event.pictures}")
    }
    LazyRow {
      items(event.pictures, key = { picture -> picture.pictureId }) { picture ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(end = 8.dp)
        ) {
          Column {
            Text(
              text = getDetectionTypeText(picture),
              style = MaterialTheme.typography.body2,
              color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (picture.picturePath.isNotEmpty()) {
              Box(
                modifier = Modifier
                  .size(24.dp)
                  .clip(CircleShape)
                  .background(Color(0xFFF5F5F5)),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = DevicePlaceholderIcon,
                  contentDescription = null,
                  tint = Color.Unspecified,
                  modifier = Modifier.size(14.dp)
                )
                AsyncImage(
                  model = rememberEventImageRequest(picture),
                  contentDescription = null,
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxSize(),
                  onError = { error ->
                    appLogger.e("[IMG-CACHE]", "picture decode error: ${picture.pictureId}, error=${error.result.throwable?.message}")
                  },
                  onSuccess = {
                    appLogger.i("[IMG-CACHE] picture displayed: ${picture.pictureId}")
                  }
                )
              }
            }
          }
        }
      }
    }
    if (event.pictures.isEmpty()) {
      Text(text = getDetectionTypeText(event.thumbnail))
    }
  }
  Column {
    Text(
      text = event.startTime.substringAfter(" "),
      style = MaterialTheme.typography.body2,
      fontSize = 14.sp,
      color = Color.Gray
    )
    Text(
      text = remember { (event.endTimestamp - event.startTimestamp).msToDurationStringWithoutHour() },
      style = MaterialTheme.typography.body2,
      modifier = Modifier.padding(top = 8.dp),
      fontSize = 14.sp,
      color = Color.Gray
    )
  }
}

@Composable
private fun getDetectionTypeText(picture: MegaPicture): String {
  return when (picture.detectionType) {
    DetectionType.HUMAN.value, DetectionType.FACE.value -> "Human"
    DetectionType.CAR.value -> "Vehicle"
    DetectionType.CRY.value -> "Crying"
    DetectionType.PACKAGE_PUT.value, DetectionType.PACKAGE_TAKE.value -> "Package"
    DetectionType.PET.value -> "Pet"
    DetectionType.SOUND.value -> "Sound"
    DetectionType.MOTION.value -> "Motion"
    DetectionType.ANSWERED_RING.value -> "Answered"
    DetectionType.MISSED_RING.value -> "Missed"
    DetectionType.RING.value -> "Ring"
    DetectionType.ALERT.value -> "Alert"
    else -> ""
  }
}