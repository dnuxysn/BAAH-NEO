// Adapted from BAAH-AOS (MaaFwApp-derived, AGPL-3.0); see third_party/baah-adapter.
package com.baah.nativehost.legacy
import com.baah.nativehost.NeoText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.baah.nativehost.legacy.BaahConfigField

@Composable
internal fun BaahNumberEditor(field: BaahConfigField, initial: Number, drafts: MutableMap<String, String>,
    onInvalid: (Boolean) -> Unit, compact: Boolean = false, showLabel: Boolean = true, compactHeight: Int = 34, onChanged: (Any) -> Unit) {
    val metadata = field.metadata
    val integer = if (metadata?.has("precision") == true) metadata.optInt("precision") == 0 else initial is Int || initial is Long
    val minimum = metadata?.takeIf { it.has("min") }?.optDouble("min")
    val maximum = metadata?.takeIf { it.has("max") }?.optDouble("max")
    val step = metadata?.optDouble("step", 1.0) ?: 1.0
    val editable = metadata?.optBoolean("readOnly") != true
    val text = drafts[field.key] ?: initial.toString()
    var editing by remember(field.key) { mutableStateOf(false) }
    var input by remember(field.key) { mutableStateOf(text) }
    val number = text.toDoubleOrNull()?.takeIf { it.isFinite() }
    val valid = number != null && (minimum == null || number >= minimum) &&
        (maximum == null || number <= maximum) && (!integer || number == number.toLong().toDouble())
    fun change(value: String) {
        drafts[field.key] = value
        val parsed = value.toDoubleOrNull()?.takeIf { it.isFinite() }
        val accepted = parsed != null && (minimum == null || parsed >= minimum) &&
            (maximum == null || parsed <= maximum) && (!integer || parsed == parsed.toLong().toDouble())
        onInvalid(!accepted)
        if (accepted) onChanged(if (integer) parsed.toLong() else parsed)
    }
    fun adjust(direction: Int) {
        var result = (number ?: initial.toDouble()) + direction * step
        if (minimum != null) result = maxOf(result, minimum)
        if (maximum != null) result = minOf(result, maximum)
        change(if (integer) result.toLong().toString() else java.math.BigDecimal.valueOf(result).stripTrailingZeros().toPlainString())
    }
    if (showLabel) Text(field.label, fontSize = if (compact) 11.sp else 14.sp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(Modifier.weight(1f).height(if (compact) compactHeight.dp else 44.dp).clickable(editable) { input = text; editing = true },
            shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Box(contentAlignment = Alignment.Center) { Text(text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (!compact) OutlinedButton({ adjust(-1) }, Modifier.width(34.dp).height(44.dp), enabled = editable, shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) { Text("−") }
        if (!compact) OutlinedButton({ adjust(1) }, Modifier.width(34.dp).height(44.dp), enabled = editable, shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) { Text("+") }
    }
    if (!valid) Text("请输入范围内的有效数字", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
    if (editing) {
        val parsed = input.toDoubleOrNull()?.takeIf { it.isFinite() }
        val accepted = parsed != null && (minimum == null || parsed >= minimum) &&
            (maximum == null || parsed <= maximum) && (!integer || parsed == parsed.toLong().toDouble())
        fun stepInput(direction: Int) {
            var result = (parsed ?: number ?: initial.toDouble()) + direction * step
            minimum?.let { result = maxOf(result, it) }
            maximum?.let { result = minOf(result, it) }
            input = if (integer) result.toLong().toString() else java.math.BigDecimal.valueOf(result).stripTrailingZeros().toPlainString()
        }
        BaahDialog(onDismissRequest = { editing = false }, title = { Text(field.label) }, text = {
            Column {
                Surface(shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp,
                    if (accepted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error)) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(input, { input = it }, Modifier.weight(1f).padding(start = 12.dp, end = 6.dp), singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal))
                    Column(Modifier.width(44.dp)) {
                        TextButton({ stepInput(1) }, Modifier.fillMaxWidth().height(28.dp), contentPadding = PaddingValues(0.dp)) { Text("▲", fontSize = 12.sp) }
                        TextButton({ stepInput(-1) }, Modifier.fillMaxWidth().height(28.dp), contentPadding = PaddingValues(0.dp)) { Text("▼", fontSize = 12.sp) }
                    }
                }
                }
                if (!accepted) Text("请输入范围内的有效数字", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }, confirmButton = { TextButton({ change(input); editing = false }, enabled = accepted) { Text("确定") } },
            dismissButton = { TextButton({ editing = false }) { Text("取消") } })
    }
}
