package de.connect2x.tammy.telecryptModules.call.callRtc

actual fun currentTimeMillis(): Long = kotlin.js.Date.now().toLong()
