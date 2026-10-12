package com.anker.esiotkit.sample.main.mega.add_device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.view.BaseScreen
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.anker.esiotkit.sample.main.mega.add_device.device_family.Device
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_add_device_select_home_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/7/23
 */
class MegaSelectHomeScreen(
  val detailsItem: Device
) : BaseScreen() {
  override val title: StringResource get() = Res.string.eslot_add_device_select_home_title

  data class HomeType(val id: Int, val name: String, val icon: ImageVector)

  @Composable
  override fun Content() {

    val navigator = LocalNavigator.currentOrThrow

    var selectedHomeId by rememberSaveable { mutableStateOf(1) }

    val homeList = listOf(
      HomeType(1, "hym***'s", Icons.Filled.Home),
      HomeType(2, "zhg***'s", Icons.Filled.Home)
    )

    SelectHomeScreen(
      homeList = homeList,
      selectedHomeId = selectedHomeId,
      onHomeSelected = { homeId -> selectedHomeId = homeId },
      onAddNewHome = { navigator.push(MegaAddNewHomeScreen) },
      onNextClick = { }
    )

  }

  @Composable
  fun SelectHomeScreen(
    homeList: List<HomeType>,
    selectedHomeId: Int,
    onHomeSelected: (Int) -> Unit,
    onAddNewHome: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier
  ) {
    Column(modifier = modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
      Text(
        text = "Select Home",
        fontSize = 16.sp,
        color = Color.Gray,
        modifier = Modifier.padding(start = 16.dp)
      )
      LazyColumn {
        items(homeList) { home ->
          HomeItem(
            home = home,
            isSelected = home.id == selectedHomeId,
            onClick = { onHomeSelected(home.id) }
          )
        }
      }
      AddNewHomeItem(onClick = onAddNewHome)
      Spacer(modifier = Modifier.weight(1f))
      Button(
        onClick = onNextClick,
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp)
          .height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(Color.Blue)
      ) {
        Text(text = "Next", color = Color.White, fontSize = 16.sp)
      }
    }
  }

  @Composable
  fun HomeItem(
    home: HomeType,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
  ) {
    Row(
      modifier = modifier
        .fillMaxWidth()
        .padding(8.dp)
        .background(Color.White, shape = RoundedCornerShape(8.dp))
        .clickable { onClick() }
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        imageVector = home.icon,
        contentDescription = "Home",
        modifier = Modifier.size(40.dp)
      )
      Spacer(modifier = Modifier.width(16.dp))
      Text(
        text = home.name,
        fontSize = 16.sp,
        color = Color.Black,
        modifier = Modifier.weight(1f)
      )
      if (isSelected) {
        Icon(
          imageVector = Icons.Filled.Check,
          contentDescription = "Selected",
          tint = Color.Blue,
          modifier = Modifier.size(24.dp)
        )
      }
    }
  }

  @Composable
  fun AddNewHomeItem(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
      modifier = modifier
        .fillMaxWidth()
        .padding(8.dp)
        .background(Color.White, shape = RoundedCornerShape(8.dp))
        .clickable { onClick() }
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center
    ) {
      Icon(
        imageVector = Icons.Default.Add,
        contentDescription = "Add",
        modifier = Modifier.size(30.dp),
        tint = Color.Blue
      )
      Spacer(modifier = Modifier.width(16.dp))
      Text(
        text = "Add New Home",
        fontSize = 16.sp,
        color = Color.Blue,
      )
    }
  }

}



