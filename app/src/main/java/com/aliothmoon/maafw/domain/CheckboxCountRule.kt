package com.aliothmoon.maafw.domain

import com.baah.nativehost.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf

fun checkboxCountRule(minCount: Int, maxCount: Int?): UiText? = when {
    maxCount == null -> if (minCount > 0) uiTextOf(R.string.option_checkbox_count_at_least, minCount) else null
    minCount == maxCount -> uiTextOf(R.string.option_checkbox_count_exactly, minCount)
    minCount > 0 -> uiTextOf(R.string.option_checkbox_count_between, minCount, maxCount)
    else -> uiTextOf(R.string.option_checkbox_count_at_most, maxCount)
}
