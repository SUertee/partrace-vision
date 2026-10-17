package com.anker.esiotkit.sample.main.mega.device_settings.device_capability

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.koin.koinScreenModel
import com.anker.esiotkit.net_api.response.thing.TDAction
import com.anker.esiotkit.net_api.response.thing.TDEvent
import com.anker.esiotkit.net_api.response.thing.TDProperty
import com.anker.esiotkit.sample.ui.composable.state
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonButton
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_debug_device_capability_title
import org.jetbrains.compose.resources.StringResource

class DeviceCapabilityScreen : BaseScreen() {

    override val title: StringResource get() = Res.string.eslot_debug_device_capability_title

    @Composable
    override fun Content() {
        val viewModel = koinScreenModel<DeviceCapabilityViewModel>()
        BaseColumn {
            DevicePicker(
                deviceList = viewModel.deviceList,
                selected = viewModel.selectedSn,
                onSelect = viewModel::selectDevice,
            )
            Spacer(Modifier.height(8.dp))
            CommonButton(
                text = "Query Capabilities",
                onClick = viewModel::query,
            )
            Spacer(Modifier.height(8.dp))
            val caps = viewModel.capabilityData
            if (caps == null) {
                Text(
                    text = "No data. Select a device and press Query.",
                    color = Color.Gray,
                )
            } else {
                CapabilitiesContent(
                    properties = caps.properties,
                    actions = caps.actions,
                    events = caps.events,
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DevicePicker(
        deviceList: List<String>,
        selected: String,
        onSelect: (String) -> Unit,
    ) {
        var expanded by state(false)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selected.ifBlank { "No devices" },
                onValueChange = {},
                readOnly = true,
                label = { Text("Device SN") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                deviceList.forEach { sn ->
                    DropdownMenuItem(
                        text = { Text(sn) },
                        onClick = {
                            onSelect(sn)
                            expanded = false
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun CapabilitiesContent(
        properties: List<TDProperty>,
        actions: List<TDAction>,
        events: List<TDEvent>,
    ) {
        val total = properties.size + actions.size + events.size
        Text(
            text = "Total: $total  (Properties: ${properties.size} / Actions: ${actions.size} / Events: ${events.size})",
            color = Color.Gray,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            if (properties.isNotEmpty()) {
                item { SectionHeader("Properties (${properties.size})") }
                items(properties) { PropertyItem(it) }
            }
            if (actions.isNotEmpty()) {
                item { SectionHeader("Actions (${actions.size})") }
                items(actions) { ActionItem(it) }
            }
            if (events.isNotEmpty()) {
                item { SectionHeader("Events (${events.size})") }
                items(events) { EventItem(it) }
            }
        }
    }

    @Composable
    private fun SectionHeader(title: String) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
    }

    @Composable
    private fun PropertyItem(prop: TDProperty) {
        CapabilityCard {
            Text(prop.identifier, fontWeight = FontWeight.Medium)
            Text(prop.desc.ifBlank { "-" }, color = Color.Gray, fontSize = 12.sp)
            Text("access: ${prop.accessMode}", color = Color.Gray, fontSize = 11.sp)
        }
    }

    @Composable
    private fun ActionItem(action: TDAction) {
        CapabilityCard {
            Text(action.identifier, fontWeight = FontWeight.Medium)
            Text(action.desc.ifBlank { "-" }, color = Color.Gray, fontSize = 12.sp)
        }
    }

    @Composable
    private fun EventItem(event: TDEvent) {
        CapabilityCard {
            Text(event.identifier, fontWeight = FontWeight.Medium)
            Text(event.desc.ifBlank { "-" }, color = Color.Gray, fontSize = 12.sp)
            Text("type: ${event.eventType}", color = Color.Gray, fontSize = 11.sp)
        }
    }

    @Composable
    private fun CapabilityCard(content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
        ) {
            Column(modifier = Modifier.padding(8.dp), content = content)
        }
    }
}
