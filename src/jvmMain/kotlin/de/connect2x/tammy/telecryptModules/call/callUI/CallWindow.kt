package de.connect2x.tammy.telecryptModules.call.callUI

/**
 * Desktop call window placeholder.
 *
 * The incoming call overlay is handled by [IncomingCallScreen] which is composed
 * inside [CallTheme] (see CallTheme.kt line 138). It renders as a full-screen
 * Dialog on top of the main application window — no separate window is needed.
 *
 * The actual call media (video/audio) is handled by Element Call running in the
 * system browser (see CallLauncher.desktop.kt). TeleCrypt opens Element Call via
 * the browser and does not embed WebRTC media directly.
 *
 * This file is intentionally left without functional code. If a dedicated desktop
 * call window is needed in the future (e.g., for a native WebRTC implementation),
 * it should be implemented here.
 */
