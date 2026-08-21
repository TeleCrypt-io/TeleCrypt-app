package de.connect2x.tammy.telecryptModules.call.callRtc

import kotlin.time.Clock

actual fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
