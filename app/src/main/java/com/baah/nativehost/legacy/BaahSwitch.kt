// Adapted from BAAH-AOS (MaaFwApp-derived, AGPL-3.0); see third_party/baah-adapter.
package com.baah.nativehost.legacy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Same 40×26 dp / 18 dp track geometry as old BAAH's BaahSwitch. */
@Composable
fun BaahSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true, large: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.size(if (large) 46.dp else 40.dp, if (large) 34.dp else 26.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)) {
        val height = (if (large) 28.dp else 18.dp).toPx()
        val radius = height / 2
        val top = (size.height - height) / 2
        val opacity = if (enabled) 1f else 0.38f
        drawRoundRect((if (checked) colors.primary else colors.surfaceContainer).copy(alpha = opacity),
            Offset(0f, top), Size(size.width, height), CornerRadius(radius))
        if (!checked) drawRoundRect(colors.outline.copy(alpha = opacity), Offset(0f, top), Size(size.width, height),
            CornerRadius(radius), style = Stroke(0.5.dp.toPx()))
        drawCircle((if (checked) colors.onPrimary else colors.outline).copy(alpha = opacity), (if (large) 11.dp else 7.dp).toPx(),
            Offset(if (checked) size.width - radius else radius, size.height / 2))
    }
}
