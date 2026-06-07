package de.connect2x.tammy.telecryptModules.call

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Logging for the call / widget-bridge subsystem.
 *
 * Replaces the ad-hoc `println` calls used during bring-up. Routed through
 * KotlinLogging (the same logger the rest of the app uses) so call output
 * obeys the project's log configuration instead of spamming stdout.
 *
 * - [callLog] — operational events (info level).
 * - [callLogDebug] — verbose diagnostics (debug level, off by default).
 */
private val log = KotlinLogging.logger("TeleCryptCall")

internal fun callLog(message: Any? = "") = log.info { message.toString() }

internal fun callLogDebug(message: Any? = "") = log.debug { message.toString() }
