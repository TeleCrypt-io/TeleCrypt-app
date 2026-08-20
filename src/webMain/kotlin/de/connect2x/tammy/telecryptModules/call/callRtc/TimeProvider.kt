package de.connect2x.tammy.telecryptModules.call.callRtc

import kotlin.js.js

actual fun currentTimeMillis(): Long = (js("Date.now()") as Double).toLong()
