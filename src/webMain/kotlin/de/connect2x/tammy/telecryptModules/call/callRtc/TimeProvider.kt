package de.connect2x.tammy.telecryptModules.call.callRtc

import kotlin.js.js

private fun jsNow(): Double = js("Date.now()")

actual fun currentTimeMillis(): Long = jsNow().toLong()
