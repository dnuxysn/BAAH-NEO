package com.baah.nativehost.legacy

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File

object NeoLocale {
    var language by androidx.compose.runtime.mutableStateOf("zh")
    fun resolve(value: String, system: String = java.util.Locale.getDefault().language) =
        (if (value == "system" || value.isBlank()) system else value).let { if (it.startsWith("en", true)) "en" else "zh" }
}
fun projectLanguage() = NeoLocale.language
object BaahStorage {
    fun configs(context: Context) = File(context.filesDir, "baah/BAAH_CONFIGS")
    fun upstream(context: Context) = File(context.filesDir, "baah")
}
