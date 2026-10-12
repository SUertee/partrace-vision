package com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.sample.main.mega.device_settings.PTZSettingsViewModel
import com.anker.esiotkit.sample.main.mega.live_video.MegaLiveStreamAction
import com.anker.esiotkit.sample.main.mega.live_video.MegaLiveStreamViewModel
import com.anker.esiotkit.sample.main.mega.live_video.PTZControlPanel
import com.anker.esiotkit.sample.support.ActionThingContainer
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.support.model.MegaPlayType
import com.anker.esiotkit.sample.support.rememberBooleanProperty
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonButton
import com.anker.esiotkit.sample.view.CommonRow
import com.anker.esiotkit.sample.view.CommonSwitchRow
import com.anker.esiotkit.sample.view.LiveStreamPlayView
import org.koin.compose.koinInject
import kotlin.jvm.Transient
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_ptz_title

@OptIn(ExperimentalMultiplatform::class)
class PTZSettingsScreen(
  @kotlinx.serialization.Transient private val viewModel: PTZSettingsViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_ptz_title

  @Composable
  override fun Content() = BaseColumn(scrollState = rememberScrollState()) {
    val navigator = LocalNavigator.currentOrThrow
    val megaDevice = remember { viewModel.megaDevice }
    val deviceManager = koinInject<ESIotDeviceManager>()
    val liveStreamViewModel = rememberScreenModel { MegaLiveStreamViewModel(megaDevice, deviceManager) }

    DisposableEffect(Unit) {
      liveStreamViewModel.action(MegaLiveStreamAction.Play(MegaPlayType.Normal))
      // Bug #10678: 对齐 mega IcPanTiltActivity onCreate → upDateView 的读取时机
      viewModel.refreshPTZSpeed()
      onDispose {
        liveStreamViewModel.action(MegaLiveStreamAction.Exit)
      }
    }

    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(16f / 9f)
          .background(Color.Black, RoundedCornerShape(8.dp))
      ) {
        LiveStreamPlayView(
          modifier = Modifier.fillMaxSize(),
          liveMediaPlayer = liveStreamViewModel.liveMediaPlayer,
          onViewCreated = { }
        )
      }

      val supportsPTZControl = remember(megaDevice) {
        megaDevice.getAction(ThingIdentifier.PTZ_ACTION_CONTROL) != null
      }

      if (supportsPTZControl) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(16.dp),
          horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
          PTZControlPanel(
            onDismiss = {},
            onDirectionControl = { direction ->
              viewModel.controlPTZ(direction.name)
            },
            onLongPressStart = { direction ->
              viewModel.startPTZMovement(direction.name)
            },
            onLongPressStop = { direction ->
              viewModel.stopPTZMovement(direction.name)
            }
          )
        }
      }

      PropertyThingContainer(
        megaDevice = megaDevice,
        identifier = ThingIdentifier.PTZ_TURN_SPEED
      ) {
        val displaySpeed = viewModel.displayPTZSpeed
        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
          Text(
            text = "Pan-Tilt Speed: $displaySpeed",
            style = MaterialTheme.typography.titleMedium
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
          ) {
            for (level in 1..5) {
              Button(
                onClick = { viewModel.setPTZSpeed(level) },
                colors = if (displaySpeed == level)
                  ButtonDefaults.buttonColors()
                else
                  ButtonDefaults.outlinedButtonColors()
              ) {
                Text("$level")
              }
            }
          }
        }
      }

      PropertyThingContainer(
        megaDevice = megaDevice,
        identifier = ThingIdentifier.VIDEO_AUTO_CRUISE_SWITCH
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
        ) {
          val autoCruise by megaDevice.rememberBooleanProperty(ThingIdentifier.VIDEO_AUTO_CRUISE_SWITCH)
          CommonSwitchRow(
            title = "Auto-Cruise",
            state = autoCruise,
            onClick = {
              viewModel.setAutoCruise(it)
            }
          )

          if (autoCruise) {
            HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            PropertyThingContainer(
              megaDevice = megaDevice,
              identifier = "cruise_mode"
            ) {
              CommonRow(
                title = "Cruise Mode",
                description = "Select patrol pattern",
                onClick = {
                  navigator.push(CruiseModeScreen(viewModel = viewModel))
                }
              )
            }
          }
        }
      }

      val supportsAITracking = remember(megaDevice) {
        megaDevice.getProperty(ThingIdentifier.AI_TRACKING_STATUS) != null
      }

      if (supportsAITracking) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
        ) {
          CommonSwitchRow(
            title = "AI Tracking",
            state = viewModel.aiTracking,
            onClick = {
              viewModel.toggleAITracking(!viewModel.aiTracking)
            }
          )
        }
      }

      val supportsPreset = remember(megaDevice) {
        val toolbarEnumList = megaDevice.getProperty(ThingIdentifier.LIVE_BOTTOM_TOOLBAR_LIST)
          ?.td?.dataType?.specs?.enumList
        if (!toolbarEnumList.isNullOrEmpty()) {
          toolbarEnumList.any { it.value == "16" }
        } else {
          megaDevice.getAction(ThingIdentifier.QUERY_PRESET_POSITIONS) != null
        }
      }
      
      if (supportsPreset) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
        ) {
          CommonRow(
            title = "Preset Position Settings",
            description = "设置预置位",
            onClick = {
              navigator.push(PresetPositionsScreen(viewModel = viewModel, megaDevice = megaDevice))
            }
          )
        }
      }

      ActionThingContainer(
        megaDevice = megaDevice,
        identifier = ThingIdentifier.PTZ_CALIBRATE
      ) {
        CommonButton("Auto-Calibration") {
          viewModel.calibratePTZ()
        }
      }
    }
    MegaLoading(viewModel.loading)
  }
}
