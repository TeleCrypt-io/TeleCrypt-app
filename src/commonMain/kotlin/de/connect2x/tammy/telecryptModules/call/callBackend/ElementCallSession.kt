package de.connect2x.tammy.telecryptModules.call.callBackend

import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.store.AccountStore

data class ElementCallSession(
    val userId: String,
    val deviceId: String,
    val accessToken: String,
    val homeserver: String,
    val displayName: String,
    val passwordlessUser: Boolean = false,
)

suspend fun resolveElementCallSession(matrixClient: MatrixClient?): ElementCallSession? {
    if (matrixClient == null) {
        return null
    }
    val accountStore = runCatching { matrixClient.di.get<AccountStore>() }.getOrNull()
        ?: return null
    val account = runCatching { accountStore.getAccount() }.getOrNull()
        ?: return null
    val accessToken = account.accessToken?.trim().orEmpty()
    val homeserver = normalizeHomeserverUrl(
        account.baseUrl?.trim().orEmpty().ifEmpty { matrixClient.baseUrl.toString() }
    )
    if (accessToken.isEmpty() || homeserver.isEmpty()) {
        return null
    }
    val displayNameFromClient = matrixClient.displayName.value?.trim().orEmpty()
    val displayName = account.displayName?.trim().orEmpty().ifEmpty {
        displayNameFromClient.ifEmpty { account.userId.full }
    }
    return ElementCallSession(
        userId = account.userId.full,
        deviceId = account.deviceId,
        accessToken = accessToken,
        homeserver = homeserver,
        displayName = displayName,
    )
}

fun buildElementCallSessionInitScript(session: ElementCallSession): String {
    val userId = escapeJsString(session.userId)
    val deviceId = escapeJsString(session.deviceId)
    val accessToken = escapeJsString(session.accessToken)
    val displayName = escapeJsString(session.displayName)
    val passwordlessUser = if (session.passwordlessUser) "true" else "false"
    return """
        (function() {
          if (window.__telecryptInjected) {
            return;
          }
          window.__telecryptInjected = true;
          const SESSION = {"user_id":"$userId","device_id":"$deviceId","access_token":"$accessToken","passwordlessUser":$passwordlessUser,"displayName":"$displayName"};
          const TITLE = "TeleCrypt Call";
          const KEY = "matrix-auth-store";
          const FLAG = "telecrypt-session-applied";
          function getParam(name) {
            try {
              const hash = window.location.hash || "";
              const search = window.location.search || "";
              const hashQuery = hash.includes("?") ? hash.substring(hash.indexOf("?") + 1) : "";
              const hashParams = new URLSearchParams(hashQuery);
              const queryParams = new URLSearchParams(search.startsWith("?") ? search.substring(1) : search);
              return hashParams.get(name) || queryParams.get(name);
            } catch (e) {
              return null;
            }
          }
          function applySession() {
            try {
              const expected = JSON.stringify({"user_id":SESSION.user_id,"device_id":SESSION.device_id,"access_token":SESSION.access_token,"passwordlessUser":SESSION.passwordlessUser});
              const current = localStorage.getItem(KEY);
              if (current !== expected) {
                localStorage.setItem(KEY, expected);
              }
              if (current !== expected && !sessionStorage.getItem(FLAG)) {
                sessionStorage.setItem(FLAG, "1");
                location.reload();
              }
              return true;
            } catch (e) {
              return false;
            }
          }
          function enforceTitle() {
            try {
              if (document && document.title !== TITLE) {
                document.title = TITLE;
              }
            } catch (e) {}
          }
          function setDisplayName() {
            try {
              const desired = getParam("displayName") || SESSION.displayName || "";
              const input = document.querySelector('[data-testid="joincall_displayName"]');
              if (input && desired && input.value !== desired) {
                input.value = desired;
                input.dispatchEvent(new Event("input", { bubbles: true }));
              }
            } catch (e) {}
          }
          function clickIfFound(selector) {
            try {
              const el = document.querySelector(selector);
              if (el) {
                el.click();
                return true;
              }
            } catch (e) {}
            return false;
          }
          function autoJoin() {
            const flag = getParam("telecryptAutoJoin");
            if (flag === "false") {
              return;
            }
            setDisplayName();
            if (clickIfFound('[data-testid="joincall_joincall"]')) {
              return;
            }
            clickIfFound('[data-testid="lobby_joinCall"]');
          }
          function applyAudioMode() {
            try {
              const mode = getParam("telecryptCallMode");
              if (mode !== "audio") return;
              const btn = document.querySelector('[data-testid="incall_videomute"]');
              if (!btn) return;
              const pressed = btn.getAttribute("aria-pressed");
              if (pressed === "true") return;
              btn.click();
            } catch (e) {}
          }
          function hideScreenshare() {
            try {
              const hide = getParam("hideScreensharing");
              if (hide !== "true") return;
              const btn = document.querySelector('[data-testid="incall_screenshare"]');
              if (btn) {
                btn.style.display = "none";
              }
            } catch (e) {}
          }
          function tick() {
            const sessionReady = applySession();
            if (!sessionReady) {
              return;
            }
            autoJoin();
            applyAudioMode();
            hideScreenshare();
            enforceTitle();
          }
          if (typeof document !== "undefined" && document.addEventListener) {
            document.addEventListener("DOMContentLoaded", function() {
              setTimeout(enforceTitle, 50);
              setTimeout(tick, 150);
              setTimeout(tick, 600);
              setTimeout(tick, 1500);
            });
          }
          setTimeout(enforceTitle, 80);
          setTimeout(tick, 250);
          setInterval(tick, 1500);
        })();
    """.trimIndent()
}

private fun escapeJsString(value: String): String {
    val out = StringBuilder(value.length + 8)
    for (ch in value) {
        when (ch) {
            '\\' -> out.append("\\\\")
            '\"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else -> out.append(ch)
        }
    }
    return out.toString()
}

private fun normalizeHomeserverUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return ""
    }
    val withoutMatrixPath = trimmed.substringBefore("/_matrix")
    return withoutMatrixPath.trimEnd('/')
}

fun resolveHomeserverUrl(matrixClient: MatrixClient?): String {
    val raw = matrixClient?.baseUrl?.toString()?.trim().orEmpty()
    return normalizeHomeserverUrl(raw)
}
