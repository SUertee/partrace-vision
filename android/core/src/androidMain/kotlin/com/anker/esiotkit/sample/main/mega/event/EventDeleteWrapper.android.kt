package com.anker.esiotkit.sample.main.mega.event

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

@Composable
actual fun EventDeleteWrapper(
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnDelete by rememberUpdatedState(onDeleteRequest)

    Box(
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { currentOnClick() },
                onLongPress = { currentOnDelete() }
            )
        }
    ) {
        content()
    }
}