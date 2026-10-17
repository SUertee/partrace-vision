package com.anker.esiotkit.sample.main.mega.event

import androidx.compose.runtime.Composable

@Composable
expect fun EventDeleteWrapper(
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit,
    content: @Composable () -> Unit
)