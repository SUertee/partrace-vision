package com.anker.esiotkit.sample.main.mega.add_device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.BottomNavigation
import androidx.compose.material.BottomNavigationItem
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.Scaffold
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.rememberScaffoldState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import com.anker.esiotkit.sample.main.mega.add_device.device_family.MegaDeviceFamilyPage
import com.anker.esiotkit.sample.main.mega.add_device.device_family.MegaDeviceFamilyViewModel
import com.anker.esiotkit.sample.main.mega.add_device.device_family.MegaPairingTypePage
import com.anker.esiotkit.sample.view.TitleBar
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_add_device_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/10/22
 */
object MegaAddDeviceScreen : Screen {

  @Composable
  override fun Content() {
    val deviceFamilyViewModel = rememberScreenModel { MegaDeviceFamilyViewModel() }
    val deviceFamilyUiState by deviceFamilyViewModel.uiState.collectAsState()
    val scaffoldState = rememberScaffoldState()
    var selectedItem by rememberSaveable { mutableStateOf(0) }
    val items = listOf(
      BottomNavItem(
        icon = Icons.Filled.Person,
        label = "Pairing Type",
        route = "pairing_type_screen"
      ),
      BottomNavItem(
        icon = Icons.Filled.Home,
        label = "PN",
        route = "pn_screen"
      ),
    )
    Scaffold(
      modifier = Modifier.navigationBarsPadding(),
      scaffoldState = scaffoldState,
      topBar = {
        TitleBar(title = Res.string.eslot_add_device_title, showBack = true)
      },
      bottomBar = {
        BottomNavigationBar(items, selectedItem) { index ->
          selectedItem = index
        }
      }) { innerPadding ->
      Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
        HorizontalDivider(modifier = Modifier.fillMaxWidth())
        when (selectedItem) {
          0 -> MegaPairingTypePage.Content()
          1 -> MegaDeviceFamilyPage.Content(
            uiState = deviceFamilyUiState,
            onSearchQueryChange = { query -> deviceFamilyViewModel.updateSearchQuery(query) },
            onCategoryPlatformChange = { platform ->
              deviceFamilyViewModel.updateSelectedCategoryPlatform(
                platform
              )
            }
          )
        }
      }
    }
  }

  @Composable
  fun BottomNavigationBar(
    items: List<BottomNavItem>,
    selectedItem: Int,
    onItemSelected: (Int) -> Unit
  ) {
    BottomNavigation(
      backgroundColor = Color.White,
      contentColor = Color.Gray,
    ) {
      items.forEachIndexed { index, item ->
        BottomNavigationItem(
          icon = {
            Icon(
              imageVector = item.icon,
              contentDescription = item.label,
              tint = if (selectedItem == index) Color.Blue else Color.Gray
            )
          },
          label = {
            Text(
              text = item.label,
              fontSize = 16.sp,
              color = if (selectedItem == index) Color.Blue else Color.Gray
            )
          },
          selected = selectedItem == index,
          onClick = {
            onItemSelected(index)
          }
        )
      }
    }
  }

  data class BottomNavItem(
    val icon: ImageVector,
    val label: String,
    val route: String
  )
}

