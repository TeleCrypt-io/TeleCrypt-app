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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import de.connect2x.trixnity.messenger.viewmodel.room.timeline.RoomHeaderViewModel
import kotlinx.coroutines.launch


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
        val i18n = DI.get<I18nView>()

        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val callLauncher: CallLauncher = DI.get()
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
                                scope.launch {
                                    callLauncher.launchCall()
                                }
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
        }
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
