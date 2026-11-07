// Adapted from BAAH-AOS (MaaFwApp-derived, AGPL-3.0); see third_party/baah-adapter.
package com.baah.nativehost.legacy
import com.baah.nativehost.NeoText as Text

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Same dimensions and button treatment as the original BaahDialog.Shell. */
@Composable
internal fun BaahDialog(
    onDismissRequest: () -> Unit,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = { Text(if (projectLanguage() == "en") "Notice" else "提示") },
    text: (@Composable () -> Unit)? = null,
    leadingAction: (@Composable () -> Unit)? = null,
    bodyHorizontalPadding: androidx.compose.ui.unit.Dp = 18.dp,
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 18.dp).widthIn(max = 440.dp).fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Column(Modifier.padding(start = bodyHorizontalPadding, end = bodyHorizontalPadding, top = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val restoreInset = (18.dp - bodyHorizontalPadding).coerceAtLeast(0.dp)
                title?.let { ProvideTextStyle(LocalTextStyle.current.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), it) }
                text?.let { body ->
                    Box(Modifier.heightIn(max = 480.dp).weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                            ProvideTextStyle(LocalTextStyle.current.copy(fontSize = 14.sp), body)
                        }
                    }
                }
                if (confirmButton != null || dismissButton != null || leadingAction != null) Row(Modifier.fillMaxWidth()
                    .padding(horizontal = restoreInset),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    leadingAction?.let { action ->
                        Box(Modifier.height(44.dp), contentAlignment = Alignment.CenterStart) { action() }
                        Spacer(Modifier.weight(1f))
                    }
                    dismissButton?.let { action ->
                        Surface(shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp,
                            MaterialTheme.colorScheme.outline)) {
                            Box(Modifier.height(44.dp).widthIn(min = 64.dp), contentAlignment = Alignment.Center) { action() }
                        }
                    }
                    confirmButton?.let { action ->
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary) {
                            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = MaterialTheme.colorScheme.onPrimary)) {
                                Box(Modifier.height(44.dp).widthIn(min = 64.dp), contentAlignment = Alignment.Center) { action() }
                            }
                        }
                    }
                }
            }
        }
    }
}
