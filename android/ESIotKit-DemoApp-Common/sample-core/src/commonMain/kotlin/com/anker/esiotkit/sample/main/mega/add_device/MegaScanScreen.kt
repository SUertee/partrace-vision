package com.anker.esiotkit.sample.main.mega.add_device

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import com.anker.esiotkit.sample.view.TitleBar
import com.anker.esiotkit.support.log.appLogger
import org.publicvalue.multiplatform.qrcode.CodeType
import org.publicvalue.multiplatform.qrcode.ScannerWithPermissions
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_add_device_scan_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/9/29
 */
object MegaScanScreen : Screen {

  @Composable
  override fun Content() {
    val viewModel = rememberScreenModel { MegaScanViewModel() }
    TitleBar(title = Res.string.eslot_add_device_scan_title, showBack = true)

    ScannerWithPermissions(
      onScanned = {
        viewModel.onScanned(it)
        appLogger.i("MegaScanScreen/Content/onScanned:$it")
        true
      }, types = CodeType.entries.toList()
    )
  }
}