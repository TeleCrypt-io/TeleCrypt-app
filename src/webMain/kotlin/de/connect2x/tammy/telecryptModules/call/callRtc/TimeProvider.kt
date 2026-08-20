package de.connect2x.tammy.telecryptModules.call.callRtc

import kotlinx.datetime.Clock

actual fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
