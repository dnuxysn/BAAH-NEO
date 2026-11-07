// Adapted from BAAH-AOS (MaaFwApp-derived, AGPL-3.0); see third_party/baah-adapter.
package com.baah.nativehost.legacy
import com.baah.nativehost.NeoText as Text

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BaahDropdown(value: String, choices: List<Pair<String, String>>, enabled: Boolean = true, translateLabels: Boolean = true,
    onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        Surface(Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Row(Modifier.fillMaxSize().clickable(enabled) { expanded = true }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text((choices.find { it.first == value }?.second ?: value).let { if (translateLabels) com.baah.nativehost.UiStrings.text(it) else it }, Modifier.weight(1f), fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, com.baah.nativehost.UiStrings.text("选择"), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.width(with(density) { width.toDp() }),
            shape = RoundedCornerShape(10.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            choices.forEach { (key, label) ->
                androidx.compose.material3.Text(if (translateLabels) com.baah.nativehost.UiStrings.text(label) else label, Modifier.fillMaxWidth().clickable { onSelect(key); expanded = false }
                    .padding(horizontal = 14.dp, vertical = 14.dp), fontSize = 15.sp)
            }
        }
    }
}
