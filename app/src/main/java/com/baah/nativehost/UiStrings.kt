package com.baah.nativehost
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.TextUnit
import com.baah.nativehost.legacy.NeoLocale
import org.json.JSONObject
object UiStrings {
    private var translations by mutableStateOf<Map<String, String>>(emptyMap())
    private var fragments: List<Pair<String,String>> = emptyList()
    fun load(context: Context) {
        val json = context.assets.open("neo-en.json").bufferedReader().use { JSONObject(it.readText()) }
        val values = json.keys().asSequence().associateWith { json.getString(it) }
        fragments = values.entries.filter { it.key.length >= 2 }.sortedByDescending { it.key.length }.map { it.key to it.value }
        translations = values
    }
    fun text(value: String): String {
        if (NeoLocale.language != "en") return value
        translations[value]?.let { return it }
        var result = value
        for ((zh,en) in fragments) if (zh in result) result = result.replace(zh,en)
        return result
    }
}
@Composable internal fun NeoText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null, fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified, textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified, overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE, minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null, style: TextStyle = LocalTextStyle.current) {
    androidx.compose.material3.Text(text = UiStrings.text(text), modifier = modifier, color = color, fontSize = fontSize,
        fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
        textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow,
        softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout, style = style)
}
