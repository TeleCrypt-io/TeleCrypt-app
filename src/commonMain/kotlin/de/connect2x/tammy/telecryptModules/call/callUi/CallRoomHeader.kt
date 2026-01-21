package de.connect2x.messenger.compose.view.room.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import de.connect2x.messenger.compose.view.DI
import de.connect2x.messenger.compose.view.common.UserState
import de.connect2x.messenger.compose.view.common.icons.PublicIcon
import de.connect2x.messenger.compose.view.common.icons.UnencryptedIcon
import de.connect2x.messenger.compose.view.common.modifier.minHeaderHeight
import de.connect2x.messenger.compose.view.get
import de.connect2x.messenger.compose.view.i18n.I18nView
import de.connect2x.messenger.compose.view.theme.components
import de.connect2x.messenger.compose.view.theme.components.AvatarPresenceBadge
import de.connect2x.messenger.compose.view.theme.components.SurfaceStyle
import de.connect2x.messenger.compose.view.theme.components.ThemedButton
import de.connect2x.messenger.compose.view.theme.components.ThemedIconButton
import de.connect2x.messenger.compose.view.theme.components.ThemedLabel
import de.connect2x.messenger.compose.view.theme.components.ThemedSurface
import de.connect2x.messenger.compose.view.theme.components.ThemedUserAvatar
import de.connect2x.tammy.telecryptModules.call.callBackend.CallLauncher
import de.connect2x.tammy.telecryptModules.call.callBackend.buildElementCallUrl
import de.connect2x.tammy.telecryptModules.call.callBackend.resolveElementCallSession
import de.connect2x.tammy.telecryptModules.call.callBackend.resolveHomeserverUrl
import de.connect2x.trixnity.messenger.viewmodel.room.timeline.RoomHeaderViewModel
import kotlinx.coroutines.launch
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent


// exact replica of HeaderSurface from de.connect2x.messenger.compose.view.common
// for some reason HeaderSurface function is marked internal in that package so we have to copy-paste it
@Composable
fun HeaderSurface(
    style: SurfaceStyle = MaterialTheme.components.header,
    content: @Composable () -> Unit,
) {
    val localElevation = LocalAbsoluteTonalElevation.current

    CompositionLocalProvider(
        LocalAbsoluteTonalElevation provides 0.dp
    ) {
        ThemedSurface(
            style = style,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CompositionLocalProvider(
                LocalAbsoluteTonalElevation provides LocalAbsoluteTonalElevation.current + localElevation
            ) {
                content()
            }
        }
    }
}

class CallRoomHeader : RoomHeaderView {
    @Composable
    override fun create(
        roomHeaderViewModel: RoomHeaderViewModel,
        showSettingsButton: Boolean,
        showBackButton: Boolean,
    ) {
        val roomHeaderElement = roomHeaderViewModel.roomHeaderInfo.collectAsState().value
        val usersTyping = roomHeaderViewModel.usersTyping.collectAsState().value
        val knockingMembersCount = roomHeaderViewModel.knockingMembersCount.collectAsState().value
        val isDirectChat = roomHeaderViewModel.isDirectChat.collectAsState().value
        val i18n = DI.get<I18nView>()

        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val callLauncher: CallLauncher = DI.get()
        var showCallDialog by remember { mutableStateOf(false) }
        val startCall: (CallMode) -> Unit = { mode ->
            scope.launch {
                val roomName = roomHeaderElement.roomName ?: "TeleCrypt Call"
                val resolvedRoomId = resolveRoomId(roomHeaderViewModel)
                val matrixClient = resolveMatrixClient(roomHeaderViewModel)
                if (resolvedRoomId == null || matrixClient == null) {
                    snackbarHostState.showSnackbar("Call unavailable. Open the room and try again.")
                    return@launch
                }
                val session = resolveElementCallSession(matrixClient)
                if (session == null) {
                    snackbarHostState.showSnackbar("Call unavailable. Please re-login.")
                    return@launch
                }
                val displayName = session.displayName.ifBlank { resolveDisplayName(matrixClient) }
                val homeserverUrl = session.homeserver.ifBlank {
                    resolveHomeserverUrl(matrixClient).ifBlank { "" }
                }.ifBlank { null }
                val callMode = mode.name.lowercase()
                val intent = if (isDirectChat) "start_call_dm" else "start_call"
                val sendNotificationType = if (isDirectChat) "ring" else "notification"
                val waitForCallPickup = isDirectChat
                val callUrl = buildElementCallUrl(
                    resolvedRoomId.full,
                    roomName,
                    displayName,
                    intent = intent,
                    sendNotificationType = sendNotificationType,
                    skipLobby = true,
                    waitForCallPickup = waitForCallPickup,
                    homeserver = homeserverUrl,
                    callMode = callMode,
                )
                println("[Call] Launching Element Call: $callUrl")
                println("[Call] Session user=${session.userId} device=${session.deviceId} hs=${session.homeserver}")
                callLauncher.joinByUrlWithSession(callUrl, session)
                val deepLink = buildTelecryptCallDeepLink(
                    resolvedRoomId.full,
                    roomName,
                    mode,
                )
                sendCallLinkMessage(
                    matrixClient,
                    resolvedRoomId,
                    roomName,
                    deepLink,
                    mode,
                )
            }
        }
        Box {
            HeaderSurface {
                Column {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.minHeaderHeight(),
                    ) {
                        if (showBackButton) {
                            Spacer(Modifier.size(8.dp))
                            RoomBackButton(roomHeaderViewModel)
                        }
                        Row(
                            Modifier
                                .padding(vertical = 4.dp)
                                .align(Alignment.CenterVertically)
                                .fillMaxWidth()
                                .weight(1f, true),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.size(8.dp))

                            ThemedButton(
                                style = MaterialTheme.components.accountSelector,
                                onClick = { roomHeaderViewModel.openRoomSettings() },
                                modifier = Modifier.semantics(mergeDescendants = true) {

                                }
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.semantics {
                                        text = AnnotatedString(roomHeaderElement.roomName)
                                        role = Role.Button
                                    }
                                ) {
                                    Box {
                                        ThemedUserAvatar(
                                            initials = roomHeaderElement.roomImageInitials,
                                            image = roomHeaderElement.roomImage,
                                            presence = roomHeaderElement.presence,
                                        ) {
                                            AvatarPresenceBadge(roomHeaderElement.presence)
                                        }
                                        if (roomHeaderElement.isPublic) {
                                            PublicIcon()
                                        }
                                    }
                                    Spacer(Modifier.size(5.dp))
                                    UserState(roomHeaderViewModel.userTrustLevel, roomHeaderViewModel.isUserBlocked)
                                    if (roomHeaderElement.isEncrypted.not()) {
                                        UnencryptedIcon()
                                        Spacer(Modifier.size(5.dp))
                                    }

                                    if (knockingMembersCount > 0) {
                                        ThemedIconButton(
                                            style = MaterialTheme.components.commonIconButton,
                                            onClick = { roomHeaderViewModel.openRoomSettings() }
                                        ) {
                                            BadgedBox(
                                                badge = {
                                                    Badge {
                                                        Text("$knockingMembersCount")
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.DoorFront,
                                                    i18n.roomHeaderKnockingUsersCount(knockingMembersCount),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.size(5.dp))
                                    }

                                    Column(modifier = Modifier.padding(end = 14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RoomName(roomHeaderElement)
                                            Spacer(Modifier.size(7.dp))
                                            if (roomHeaderElement.isLeave) {
                                                ThemedLabel(i18n.commonArchived())
                                            }
                                        }
                                        if (usersTyping != null) {
                                            UsersTyping(usersTyping)
                                        } else {
                                            RoomTopic(roomHeaderElement)
                                        }
                                    }
                                }
                            }
                        }

                        CallButton(
                            onClick = {
                                showCallDialog = true
                            },
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                        RoomExtras(roomHeaderViewModel, showSettingsButton)
                        Spacer(Modifier.size(8.dp))
                    }

                    HorizontalDivider(Modifier.fillMaxWidth())
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            )
            if (showCallDialog) {
                AlertDialog(
                    onDismissRequest = { showCallDialog = false },
                    title = { Text("Start call") },
                    text = { Text("Choose call type") },
                    confirmButton = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    showCallDialog = false
                                    startCall(CallMode.AUDIO)
                                },
                                colors = ButtonDefaults.buttonColors(),
                            ) {
                                Icon(Icons.Default.Phone, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Audio")
                            }
                            Button(
                                onClick = {
                                    showCallDialog = false
                                    startCall(CallMode.VIDEO)
                                },
                                colors = ButtonDefaults.buttonColors(),
                            ) {
                                Icon(Icons.Default.Videocam, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Video")
                            }
                        }
                    },
                    dismissButton = null,
                )
            }
        }
    }
}

private fun resolveDisplayName(matrixClient: MatrixClient?): String {
    val displayName = matrixClient?.displayName?.value?.trim().orEmpty()
    return displayName.ifEmpty { matrixClient?.userId?.full ?: "TeleCrypt User" }
}

private suspend fun sendCallLinkMessage(
    matrixClient: MatrixClient,
    roomId: RoomId,
    roomName: String,
    deepLink: String,
    mode: CallMode,
) {
    val safeRoomName = roomName.trim().ifEmpty { "Call" }
    val label = callModeLabel(mode)
    val body = "$label: $safeRoomName"
    val formattedBody = buildCallHtml(safeRoomName, deepLink, label)
    val content = RoomMessageEventContent.TextBased.Text(
        body = body,
        format = MATRIX_HTML_FORMAT,
        formattedBody = formattedBody,
        externalUrl = deepLink,
    )
    runCatching { matrixClient.api.room.sendMessageEvent(roomId, content) }
        .onFailure { println("[Call] Failed to send call link: ${it.message}") }
}

private const val MATRIX_HTML_FORMAT = "org.matrix.custom.html"

private fun buildCallHtml(roomName: String, deepLink: String, label: String): String {
    val escapedDeepLink = escapeHtml(deepLink)
    val escapedRoom = escapeHtml(roomName)
    val escapedLabel = escapeHtml(label)
    return "<a href=\"$escapedDeepLink\">Join call</a> - $escapedLabel ($escapedRoom)"
}

private fun escapeHtml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private fun buildTelecryptCallDeepLink(roomId: String, roomName: String, mode: CallMode): String {
    val encodedRoomId = encodeQueryComponent(roomId)
    val encodedRoomName = encodeQueryComponent(roomName)
    val encodedMode = encodeQueryComponent(mode.name.lowercase())
    return "com.zendev.telecrypt://call?roomId=$encodedRoomId&roomName=$encodedRoomName&mode=$encodedMode"
}

private fun encodeQueryComponent(value: String): String {
    val bytes = value.encodeToByteArray()
    val out = StringBuilder(bytes.size * 3)
    for (byte in bytes) {
        val b = byte.toInt() and 0xFF
        val ch = b.toChar()
        if (isUnreserved(ch)) {
            out.append(ch)
        } else {
            out.append('%')
            out.append(HEX[b ushr 4])
            out.append(HEX[b and 0x0F])
        }
    }
    return out.toString()
}

private fun isUnreserved(ch: Char): Boolean {
    return (ch in 'A'..'Z') ||
        (ch in 'a'..'z') ||
        (ch in '0'..'9') ||
        ch == '-' || ch == '.' || ch == '_' || ch == '~'
}

private val HEX = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7',
    '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
)

private enum class CallMode {
    AUDIO,
    VIDEO,
}

private fun callModeLabel(mode: CallMode): String {
    return when (mode) {
        CallMode.AUDIO -> "Audio call"
        CallMode.VIDEO -> "Video call"
    }
}

@Composable
fun CallButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ThemedIconButton(
        style = MaterialTheme.components.commonIconButton,
        onClick = onClick,
        modifier = modifier
    )
    {
        Icon(Icons.Default.Phone, "Call Button")
    }
}
