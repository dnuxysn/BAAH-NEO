package com.aliothmoon.maafw.project

fun isRemoteUrl(content: String): Boolean =
    content.startsWith("https://") || content.startsWith("http://")
