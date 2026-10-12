package com.anker.esiotkit.sample.support.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.icerock.moko.permissions.PermissionsController
import dev.icerock.moko.permissions.compose.PermissionsControllerFactory
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory

/**
 * @version 2024/11/26
 */
@Composable
fun rememberPermissionController(): PermissionsController {
  val factory: PermissionsControllerFactory = rememberPermissionsControllerFactory()
  val controller: PermissionsController = remember(factory) { factory.createPermissionsController() }
  return controller
}