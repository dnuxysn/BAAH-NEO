package com.baah.nativehost

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable

internal const val editionUpdateMessage = "BAAH 已更新"

@Composable internal fun BoxScope.EditionUpdateExtras(app: NeoApp, running: Boolean, busy: Boolean) = Unit
