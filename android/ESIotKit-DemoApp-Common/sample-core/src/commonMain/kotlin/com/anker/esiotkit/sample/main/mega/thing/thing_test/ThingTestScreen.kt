package com.anker.esiotkit.sample.main.mega.thing.thing_test

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Divider
import androidx.compose.material.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.net_api.response.thing.TDAction
import com.anker.esiotkit.net_api.response.thing.TDEvent
import com.anker.esiotkit.net_api.response.thing.TDProperty
import com.anker.esiotkit.sample.ui.component.DisableOverscrollEffect
import com.anker.esiotkit.sample.view.SearchBar
import com.anker.esiotkit.sample.view.TitleBar
import com.anker.esiotkit.support.log.appLogger

class ThingTestScreen(val device: MegaDevice, private val tabIndex: Int, val id: String) : Screen {

  @Composable
  override fun Content() {
    val navigator = LocalNavigator.currentOrThrow
    var selectedTab by remember { mutableStateOf(tabIndex) }
    var searchPropertyQuery by remember { mutableStateOf("") }
    var searchActionQuery by remember { mutableStateOf("") }
    var searchEventQuery by remember { mutableStateOf("") }

    val filteredEventMap = device.getThingDescription()?.eventMap?.filter {
      it.key.contains(searchEventQuery, ignoreCase = true)
    } ?: mapOf()

    val filteredPropertyMap = device.getThingDescription()?.propMap?.filter {
      it.key.contains(searchPropertyQuery, ignoreCase = true)
    } ?: mapOf()

    val filteredActionMap = device.getThingDescription()?.actionMap?.filter {
      it.key.contains(searchActionQuery, ignoreCase = true)
    } ?: mapOf()
    val tabs = listOf("Property", "Action", "Event")

    Scaffold(
      modifier = Modifier.navigationBarsPadding(),
      topBar = {
        TitleBar(
          title = "${device.deviceSn}\n${device.iotDevice.deviceName}",
          showBack = true,
          onBackClick = {
            navigator.pop()
          })
      }
    ) { padding ->
      Column(modifier = Modifier.padding(padding)) {
        TabRow(selectedTabIndex = selectedTab) {
          tabs.forEachIndexed { index, title ->
            Tab(
              selected = selectedTab == index,
              onClick = { selectedTab = index },
              text = { Text(title) }
            )
          }
        }
        when (selectedTab) {
          TAB_PROPERTY -> device.getThingDescription()?.let {
            PropertyList(filteredPropertyMap, onSearchQueryChange = { query ->
              searchPropertyQuery = query
            })
          }
          TAB_ACTION -> device.getThingDescription()?.let {
            ActionList(filteredActionMap, onSearchQueryChange = { query ->
              searchActionQuery = query
              appLogger.d("searchActionQuery:$searchActionQuery,query:$query")
            })
          }
          TAB_EVENT -> device.getThingDescription()?.let {
            EventList(filteredEventMap, onSearchQueryChange = { query ->
              searchEventQuery = query
            })
          }
        }

      }

    }

  }

  @Composable
  fun EventList(properties: Map<String, TDEvent>, onSearchQueryChange: (String) -> Unit) {
    val initText = if (tabIndex == TAB_EVENT && id.isNotEmpty()) id else ""
    SearchBar(
      "search identifier",
      modifier = Modifier.fillMaxWidth(),
      initialText = initText,
      onSearch = { query ->
        onSearchQueryChange(query)
      })
    // 禁用 overscroll 效果，解决滚动到边界时点击坐标错位的问题
    DisableOverscrollEffect {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
      ) {
        items(properties.values.toList()) { property ->
          EventItem(property)
        }
      }
    }
  }

  @Composable
  private fun EventItem(property: TDEvent) {
    var resultText by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
      // add listener
      device.getEvent(property.identifier)?.addListener(property.identifier) { payload ->
        resultText = payload.asJson()
      }
      onDispose {
        // remove listener
        device.getEvent(property.identifier)?.removeListenerBy(property.identifier)
      }
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp)
    ) {

      Column(modifier = Modifier.padding(start = 8.dp)) {
        Text(text = "id: ${property.identifier}", fontWeight = FontWeight.Bold)
        Text(text = "desc: ${property.desc}", style = MaterialTheme.typography.bodyMedium)
      }

      Divider(thickness = 1.dp, modifier = Modifier.padding(4.dp))

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp) // 设置卡片间距
      ) {
        Card(
          modifier = Modifier
            .weight(1f), // 平分父容器宽度
          colors = CardDefaults.cardColors(containerColor = Color(0xFFD0E8FF)), // 浅蓝色

        ) {
          Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            Text(text = "remark:${property.remark}")
          }
        }

        Card(
          modifier = Modifier
            .weight(1f), // 平分宽度
          colors = CardDefaults.cardColors(containerColor = Color(0xFFDFFFE0)), // 浅绿色

        ) {
          Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            Text(text = "outputData:${property.outputData?.type}")
            Text(text = "max:${property.outputData?.specs?.max}")
            Text(text = "min:${property.outputData?.specs?.min}")
            Text(text = "unit:${property.outputData?.specs?.unit}")
            Text(text = "step:${property.outputData?.specs?.step}")
            Text(text = "length:${property.outputData?.specs?.length}")
            Text(text = "enumList:${property.outputData?.specs?.enumList}")
          }
        }
      }

      Column(
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime),
      ) {
        Text(
          resultText,
        )
      }
    }
    Divider(thickness = 3.dp, modifier = Modifier.padding(4.dp))
  }

  @Composable
  fun ActionList(properties: Map<String, TDAction>, onSearchQueryChange: (String) -> Unit) {
    val initText = if (tabIndex == TAB_ACTION && id.isNotEmpty()) id else ""
    SearchBar(
      "search identifier",
      modifier = Modifier.fillMaxWidth(),
      initialText = initText,
      onSearch = { query ->
        onSearchQueryChange(query)
      })
    // 禁用 overscroll 效果，解决滚动到边界时点击坐标错位的问题
    DisableOverscrollEffect {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
      ) {
        items(properties.values.toList()) { property ->
          ActionItem(property)
        }
      }
    }
  }

  @Composable
  private fun ActionItem(property: TDAction) {
    var isLoading by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp)
    ) {

      Column(modifier = Modifier.padding(start = 8.dp)) {
        Text(text = "id: ${property.identifier}", fontWeight = FontWeight.Bold)
        Text(text = "desc: ${property.desc}", style = MaterialTheme.typography.bodyMedium)
      }

      Divider(thickness = 1.dp, modifier = Modifier.padding(4.dp))

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp) // 设置卡片间距
      ) {
        Card(
          modifier = Modifier
            .weight(1f), // 平分父容器宽度
          colors = CardDefaults.cardColors(containerColor = Color(0xFFD0E8FF)), // 浅蓝色

        ) {
          Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            Text(text = "inputData:${property.inputData?.type}")
            Text(text = "max:${property.inputData?.specs?.max}")
            Text(text = "min:${property.inputData?.specs?.min}")
            Text(text = "unit:${property.inputData?.specs?.unit}")
            Text(text = "step:${property.inputData?.specs?.step}")
            Text(text = "length:${property.inputData?.specs?.length}")
            Text(text = "enumList:${property.inputData?.specs?.enumList}")
          }
        }

        Card(
          modifier = Modifier
            .weight(1f), // 平分宽度
          colors = CardDefaults.cardColors(containerColor = Color(0xFFDFFFE0)), // 浅绿色

        ) {
          Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            Text(text = "outputData:${property.outputData?.type}")
            Text(text = "max:${property.outputData?.specs?.max}")
            Text(text = "min:${property.outputData?.specs?.min}")
            Text(text = "unit:${property.outputData?.specs?.unit}")
            Text(text = "step:${property.outputData?.specs?.step}")
            Text(text = "length:${property.outputData?.specs?.length}")
            Text(text = "enumList:${property.outputData?.specs?.enumList}")
          }
        }
      }

      Column(
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime),
      ) {

        OutlinedTextField(
          value = inputText,
          onValueChange = { inputText = it },
          label = { Text("please input ${property.inputData?.type}") },
          modifier = Modifier.fillMaxWidth()
        )
        Row {
          Button(onClick = {
            isLoading = true
            property.inputData?.type?.let {
              val payload = getPayloadByType(it, inputText)
              device.getAction(property.identifier)?.invoke(payload)?.onSuccess() { thingPayload ->
                isLoading = false
                resultText = thingPayload.asJson()

              }?.onFailure { code, message ->
                isLoading = false
                resultText = "Failed!!! code:$code,message:$message"
              }
            }

          }) {
            Text("Invoke Action")
          }
          RowItem(
            "result:",
            if (isLoading) "loading" else resultText,
            modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp)
          )
        }
      }
    }
    Divider(thickness = 3.dp, modifier = Modifier.padding(4.dp))
  }

  @Composable
  fun PropertyList(properties: Map<String, TDProperty>, onSearchQueryChange: (String) -> Unit) {
    val initText = if (tabIndex == TAB_PROPERTY && id.isNotEmpty()) id else ""
    SearchBar(
      "search identifier",
      modifier = Modifier.fillMaxWidth(),
      initialText = initText,
      onSearch = { query ->
        onSearchQueryChange(query)
      })
    // 禁用 overscroll 效果，解决滚动到边界时点击坐标错位的问题
    DisableOverscrollEffect {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
      ) {
        items(properties.values.toList()) { property ->
          PropertyItem(property)
        }
      }
    }
  }

  @Composable
  private fun PropertyItem(property: TDProperty) {
    var isLoading by remember { mutableStateOf(false) }
    var isSetLoading by remember { mutableStateOf(false) }
    var getResultText by remember { mutableStateOf("") }
    var setResultText by remember { mutableStateOf("") }
    var setInputValue by remember { mutableStateOf("") }
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp)
    ) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Text(
          text = property.accessMode,
          color = Color.White,
          textAlign = TextAlign.Center,
          modifier = Modifier
            .background(Color(0xFFFFA500))
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .width(30.dp)
            .align(Alignment.CenterVertically)
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
          Text(text = "id: ${property.identifier}", fontWeight = FontWeight.Bold)
          Text(text = "desc: ${property.desc}", style = MaterialTheme.typography.bodyMedium)
        }
      }

      Divider(thickness = 1.dp, modifier = Modifier.padding(4.dp))

      Column(modifier = Modifier.padding(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
        ) {
          RowItem("data_type:", property.dataType?.type ?: "", modifier = Modifier.weight(1f))
          RowItem("length:", property.dataType?.specs?.length.toString(), modifier = Modifier.weight(1f))
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
        ) {
          RowItem("max:", property.dataType?.specs?.max ?: "", modifier = Modifier.weight(1f))
          RowItem("min:", property.dataType?.specs?.min ?: "", modifier = Modifier.weight(1f))
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
        ) {
          RowItem("enumList:", property.dataType?.specs?.enumList.toString(), modifier = Modifier.weight(1f))
          RowItem("step:", property.dataType?.specs?.step ?: "", modifier = Modifier.weight(1f))
        }

      }



      Row(
        modifier = Modifier.fillMaxWidth(),
      ) {
        Button(onClick = {
          isLoading = true
          device.getProperty(property.identifier)?.let {
            getResultText = it.thingPayload.asJson().replace("\\\"", "\"")
            isLoading = false
          } ?: run {
            isLoading = false
          }

        }) {
          Text("GET")
        }
        RowItem(
          "result:",
          if (isLoading) "loading" else getResultText,
          modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp)
        )

      }


      if (property.accessMode.lowercase() == "rw") {
        Divider(thickness = 1.dp, modifier = Modifier.padding(4.dp))
        Column(
          modifier = Modifier.windowInsetsPadding(WindowInsets.ime)
        ) {

          //input text filed
          OutlinedTextField(
            value = setInputValue,
            onValueChange = { setInputValue = it },
            label = { Text("please input ${property.dataType?.type}") },
            modifier = Modifier.fillMaxWidth()
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
          ) {
            Button(onClick = {
              isSetLoading = true

              property.dataType?.type?.let { type ->
                device.getProperty(property.identifier)
                  ?.set(getPayloadByType(type = type, value = setInputValue))
                  ?.let {
                    it.onSuccess { resultPayload ->
                      isSetLoading = false
                      setResultText = resultPayload.asJson()

                    }
                    it.onFailure { errorCode, errorPayload ->
                      isSetLoading = false
                      setResultText = "Failed!! code:$errorCode,payload:$errorPayload"
                    }
                    it.onProgress {
                      isSetLoading = true
                    }
                  }
              }

            }) {
              Text("SET")
            }
            RowItem(
              "result:",
              if (isSetLoading) "loading" else setResultText,
              modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp)
            )

          }
        }
      }

    }
    Divider(thickness = 3.dp, modifier = Modifier.padding(4.dp))
  }

  private fun getPayloadByType(type: String, value: String?): ThingPayload {
    if (value == null) return ThingPayload.create()
    val payload = when (type) {
      "int", "long", "enum", "ienum", "tenum" -> ThingPayload.fromInt(value.toIntOrNull() ?: 0)
      "text", "string" -> ThingPayload.fromString(value)
      "bool" -> ThingPayload.from(value.toBoolean())
      "double", "float" -> ThingPayload.from(value.toDoubleOrNull() ?: 0.0)
      "json" -> ThingPayload.from(value)
      else -> ThingPayload.from(value)
    }
    return payload
  }

  @Composable
  private fun RowItem(label: String, value: String, modifier: Modifier) {
    Text(
      text = buildAnnotatedString {
        append(label)
        addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, length)
        append(value)
      }, modifier = modifier
    )
  }

  companion object {
    private const val TAB_PROPERTY = 0
    private const val TAB_ACTION = 1
    private const val TAB_EVENT = 2
  }

}