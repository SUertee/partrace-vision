package com.anker.esiotkit.sample.main.mega.device_settings.package_detection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.PropertySwitch
import com.anker.esiotkit.sample.view.currentNavigator
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.koin.compose.koinInject

class PackageDetectionScreen(
    private val deviceSn: String
) : BaseScreen() {

    override val title: StringResource
        get() = Res.string.eslot_settings_title

    @Composable
    override fun Content() = BaseColumn(paddingHorizontal = 0.dp) {
        val megaDevice = remember(deviceSn) {
            MegaDeviceManager.getDeviceBy(deviceSn)
        } ?: run {
            appLogger.w("PackageDetectionScreen/deviceNotFound: sn=$deviceSn")
            emitToast("Device not found")
            currentNavigator?.pop()
            return@BaseColumn
        }
        val deviceManager = koinInject<ESIotDeviceManager>()
        val viewModel = rememberScreenModel { PackageDetectionViewModel(deviceSn, deviceManager) }
        val scope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF5F5F5))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val guardEnabled = viewModel.isEnabled

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(12.dp))
            ) {
                if (viewModel.isSupportPackageDetection) {
                    appLogger.i("PackageDetectionScreen/render: use PropertySwitch for device_delivered_guard_switch, sn=$deviceSn")
                    PropertySwitch(
                        megaDevice = megaDevice,
                        identifier = "device_delivered_guard_switch",
                        title = "Package Detection"
                    )
                    HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }

                if (viewModel.isSupportPackageNotification) {
                    SwitchRow(
                        title = "Notification",
                        checked = viewModel.notificationEnabled,
                        enabled = guardEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                val success = viewModel.toggleNotification(enabled)
                                if (!success) emitToast("Failed to toggle notification")
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }

                if (viewModel.isSupportPackageRecording) {
                    SwitchRow(
                        title = "Recording",
                        checked = viewModel.recordingEnabled,
                        enabled = guardEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                val success = viewModel.toggleRecording(enabled)
                                if (!success) emitToast("Failed to toggle recording")
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }

                if (viewModel.isSupportUncollectedAlert) {
                    SwitchRow(
                        title = "Uncollected Package Alert",
                        checked = viewModel.uncollectedAlertEnabled,
                        enabled = guardEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                val success = viewModel.toggleUncollectedAlert(enabled)
                                if (!success) emitToast("Failed to toggle uncollected alert")
                            }
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun SwitchRow(
        title: String,
        checked: Boolean,
        enabled: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        val alpha = if (enabled) 1f else 0.3f
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = Color.Black.copy(alpha = alpha)
            )
            Switch(
                checked = checked,
                onCheckedChange = if (enabled) onCheckedChange else null,
                enabled = enabled
            )
        }
    }
}
