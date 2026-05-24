# Android Widget-Mode Testing Guide

## Step 1: Install the APK

**Option A — From Android Studio:**
Click the green ▶ Run button in the toolbar, select the Pixel 7 device. The app will install and launch automatically.

**Option B — Via command line:**
```bash
cd /Users/dsrusanov/HSE-stuff/nir
~/Library/Android/sdk/platform-tools/adb install -r \
  "TeleCrypt/build/outputs/apk/DEV/debug/TeleCrypt Messenger-DEV-debug.apk"
```

## Step 2: Set up Logcat filters

In the Logcat panel (bottom of Android Studio):
- **Filter:** `package:de.connect2x.tammy`
- Or use regex: `WidgetBridge|WidgetApi|\[Call\]|ElementCall`

## Step 3: Test scenario — Android↔Desktop

| Side | Action |
|------|--------|
| **Mac desktop** (`@testuser`) | Open TeleCrypt desktop, log in, join a room |
| **Android emulator** (`@dimarus05`) | Open TeleCrypt Android, log in, join the **same room** |
| **Desktop** | Click **Start Call** in the room |
| **Android** | Wait for incoming call notification → tap **Join** |

## Step 4: Expected log output

On Android Logcat, look for these entries in order:

```
[WidgetBridge] starting server on port=XXXXX
[WidgetBridgeManager] preloaded N state events into cache
[WidgetBridgeManager] bridge started for roomId=... widgetId=...
[WidgetBridge] WS connected widgetId=telecrypt-...
[WidgetBridge] doSendToDevice ... ok=true
[WidgetBridge] doReadStateEvents ... found=N
```

## Step 5: Verify success

- ✅ Both sides see each other's video
- ✅ No `BAD_MESSAGE_MAC` errors in logs
- ✅ No `doReadStateEvents` timeouts (the Windows blocker)
- ✅ Element Call loads within ~5 seconds (not 5 minutes)

## If it fails

Check Logcat for:
- `WidgetBridge` not found → `AndroidWidgetBridgeManager` not wired up
- `network_security_config` errors → cleartext HTTP blocked
- `MIXED_CONTENT` errors → HTTPS EC iframe inside HTTP host page blocked
- Port binding errors → something else is using the port
