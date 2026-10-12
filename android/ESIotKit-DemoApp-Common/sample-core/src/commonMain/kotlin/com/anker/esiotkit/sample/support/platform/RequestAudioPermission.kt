package com.anker.esiotkit.sample.support.platform

import androidx.compose.runtime.Composable
import com.anker.esiotkit.sample.support.platform.permission.MegaPermissionState

/**
 * @version 2024/9/9
 */
@Composable
expect fun rememberAudioPermissionState(): MegaPermissionState

@Composable
expect fun rememberBlePermissionState(): MegaPermissionState