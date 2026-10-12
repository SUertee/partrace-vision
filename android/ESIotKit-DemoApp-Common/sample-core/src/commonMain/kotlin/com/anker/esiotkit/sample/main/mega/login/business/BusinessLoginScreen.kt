package com.anker.esiotkit.sample.main.mega.login.business

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.main.mega.MegaMainScreen
import com.anker.esiotkit.sample.support.exitSampleApplication
import com.anker.esiotkit.sample.ui.composable.BackHandler
import com.anker.esiotkit.sample.ui.composable.touchToHideKeyboard
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonButton
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_login_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/11/6
 */
object BusinessLoginScreen : BaseScreen() {
  override val title: StringResource get() = Res.string.eslot_login_title
  override val showTitleBack: Boolean
    get() = false
  @Composable
  override fun Content() = BaseColumn(
    imePadding = true,
    scrollState = rememberScrollState(),
    modifier = Modifier.touchToHideKeyboard()
  ) {
    val viewModel = rememberScreenModel { BusinessLoginViewModel() }
    val navigator = LocalNavigator.currentOrThrow
    Environment(viewModel)
    OutlinedTextField(
      value = viewModel.appName,
      onValueChange = { viewModel.appName = it },
      label = { Text("App Name") },
      trailingIcon = {
        if (viewModel.appName.isNotEmpty()) {
          IconButton(onClick = { viewModel.appName = "" }) {
            Icon(Icons.Default.Close, contentDescription = "Clear App Name")
          }
        }
      },
    )
    OutlinedTextField(
      value = viewModel.clientId,
      onValueChange = { viewModel.clientId = it },
      label = { Text("Client ID") },
      trailingIcon = {
        if (viewModel.clientId.isNotEmpty()) {
          IconButton(onClick = { viewModel.clientId = "" }) {
            Icon(Icons.Default.Close, contentDescription = "Clear Client ID")
          }
        }
      },
    )
    OutlinedTextField(
      value = viewModel.userId,
      onValueChange = { viewModel.userId = it },
      label = { Text("User ID") },
      trailingIcon = {
        if (viewModel.userId.isNotEmpty()) {
          IconButton(onClick = { viewModel.userId = "" }) {
            Icon(Icons.Default.Close, contentDescription = "Clear User ID")
          }
        }
      },
    )
    OutlinedTextField(
      value = viewModel.token,
      onValueChange = { viewModel.token = it },
      label = { Text("Token") },
      trailingIcon = {
        if (viewModel.token.isNotEmpty()) {
          IconButton(onClick = { viewModel.token = "" }) {
            Icon(Icons.Default.Close, contentDescription = "Clear Token")
          }
        }
      },
    )
    CommonButton("Login") {
      viewModel.login()
      navigator.push(MegaMainScreen)
    }
    Spacer(modifier = Modifier.weight(1f))
    BackHandler {
      exitSampleApplication()
    }
  }

  @Composable
  private fun Environment(viewModel: BusinessLoginViewModel) {
    Column {
      Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
          selected = viewModel.environment == BusinessLoginConfig.ENV_PR,
          onClick = { viewModel.switchToPR() },
        )
        Text(text = "US-PR")
      }
    }
  }
}