package dev.jtiisto.wellness.core.common

import java.util.UUID

fun generateId(): String = UUID.randomUUID().toString()

fun generateShortId(): String = UUID.randomUUID().toString().take(8)
