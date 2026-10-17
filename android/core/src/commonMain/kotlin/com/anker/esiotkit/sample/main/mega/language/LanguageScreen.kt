package com.anker.esiotkit.sample.main.mega.language

import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_language_mode_en
import com.anker.esiotkit.demo.generated.resources.eslot_language_mode_system
import com.anker.esiotkit.demo.generated.resources.eslot_language_mode_zh
import com.anker.esiotkit.demo.generated.resources.eslot_language_title

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.support.i18n.AppLanguage
import com.anker.esiotkit.sample.support.i18n.LanguageManager
import com.anker.esiotkit.sample.view.TitleBar
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringResource

object LanguageScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val currentMode by LanguageManager.currentMode.collectAsState()

        var lastClickTime by remember { mutableStateOf(0L) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
        ) {
            TitleBar(title = Res.string.eslot_language_title)

            LanguageOptionRow(
                label = stringResource(Res.string.eslot_language_mode_system),
                selected = currentMode == AppLanguage.SYSTEM,
                onClick = {
                    val now = Clock.System.now().toEpochMilliseconds()
                    if (now - lastClickTime > 500) {
                        lastClickTime = now
                        LanguageManager.setLanguage(AppLanguage.SYSTEM)
                    }
                }
            )

            HorizontalDivider()

            LanguageOptionRow(
                label = stringResource(Res.string.eslot_language_mode_en),
                selected = currentMode == AppLanguage.EN,
                onClick = {
                    val now = Clock.System.now().toEpochMilliseconds()
                    if (now - lastClickTime > 500) {
                        lastClickTime = now
                        LanguageManager.setLanguage(AppLanguage.EN)
                    }
                }
            )

            HorizontalDivider()

            LanguageOptionRow(
                label = stringResource(Res.string.eslot_language_mode_zh),
                selected = currentMode == AppLanguage.ZH,
                onClick = {
                    val now = Clock.System.now().toEpochMilliseconds()
                    if (now - lastClickTime > 500) {
                        lastClickTime = now
                        LanguageManager.setLanguage(AppLanguage.ZH)
                    }
                }
            )
        }
    }

    @Composable
    private fun LanguageOptionRow(
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 16.sp,
                color = Color(0xFF1F1F1F),
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color(0xFF005D8E),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
