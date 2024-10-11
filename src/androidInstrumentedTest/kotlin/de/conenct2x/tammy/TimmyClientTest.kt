package de.conenct2x.tammy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import de.connect2x.messenger.compose.view.*
import de.connect2x.messenger.compose.view.i18n.i18nViewModule
import de.connect2x.messenger.compose.view.room.RoomSwitch
import de.connect2x.messenger.compose.view.roomlist.RoomListSwitch
import de.connect2x.messenger.compose.view.theme.MessengerTheme
import de.connect2x.tammy.tammyModule
import de.connect2x.trixnity.messenger.MatrixMessengerConfiguration
import de.connect2x.trixnity.messenger.MatrixMessengerSettingsHolder
import de.connect2x.trixnity.messenger.i18n.DefaultLanguages
import de.connect2x.trixnity.messenger.i18n.I18n
import de.connect2x.trixnity.messenger.i18n.Languages
import de.connect2x.trixnity.messenger.i18n.platformGetSystemLangModule
import de.connect2x.trixnity.messenger.platformMatrixMessengerSettingsHolderModule
import de.connect2x.trixnity.messenger.util.FileDescriptor
import de.connect2x.trixnity.messenger.util.FileTransferProgressElement
import de.connect2x.trixnity.messenger.util.RootPath
import de.connect2x.trixnity.messenger.viewmodel.AccountInfo
import de.connect2x.trixnity.messenger.viewmodel.MainViewModel
import de.connect2x.trixnity.messenger.viewmodel.UserInfoElement
import de.connect2x.trixnity.messenger.viewmodel.files.MediaRouter
import de.connect2x.trixnity.messenger.viewmodel.initialsync.InitialSyncRouter
import de.connect2x.trixnity.messenger.viewmodel.room.RoomRouter
import de.connect2x.trixnity.messenger.viewmodel.room.RoomViewModel
import de.connect2x.trixnity.messenger.viewmodel.room.settings.SettingsRouter
import de.connect2x.trixnity.messenger.viewmodel.room.timeline.*
import de.connect2x.trixnity.messenger.viewmodel.room.timeline.elements.*
import de.connect2x.trixnity.messenger.viewmodel.room.timeline.elements.util.MessageMention
import de.connect2x.trixnity.messenger.viewmodel.roomlist.*
import de.connect2x.trixnity.messenger.viewmodel.settings.AvatarCutterRouter
import de.connect2x.trixnity.messenger.viewmodel.util.ErrorType
import de.connect2x.trixnity.messenger.viewmodel.verification.SelfVerificationRouter
import de.connect2x.trixnity.messenger.viewmodel.verification.VerificationRouter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import net.folivo.trixnity.client.key.UserTrustLevel
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.m.Presence
import net.folivo.trixnity.core.model.events.m.room.EncryptedFile
import net.folivo.trixnity.utils.ByteArrayFlow
import net.folivo.trixnity.utils.nextString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Rule
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.cleanstatusbar.BluetoothState
import tools.fastlane.screengrab.cleanstatusbar.CleanStatusBar
import tools.fastlane.screengrab.cleanstatusbar.IconVisibility
import tools.fastlane.screengrab.cleanstatusbar.MobileDataType
import tools.fastlane.screengrab.locale.LocaleTestRule
import tools.fastlane.screengrab.locale.LocaleUtil
import java.util.*
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

val MAX_WIDTH = 1600.dp

@OptIn(ExperimentalTestApi::class)
class TimmyClientTest {

    @Rule
    @JvmField
    val localeTestRule = LocaleTestRule()

    @BeforeTest
    fun before() {
        CleanStatusBar()
            .setBluetoothState(BluetoothState.DISCONNECTED)
            .setMobileNetworkDataType(MobileDataType.LTE)
            .setWifiVisibility(IconVisibility.HIDE)
            .setShowNotifications(false)
            .setClock("0900")
            .setBatteryLevel(100)
            .enable()
    }

    @AfterTest
    fun after() {
        CleanStatusBar.disable()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test
    fun timmyScreen() = runTest {
        val di = koinApplication {
            modules(
                // TODO this needs to be removed and fixed, as there is no MatrixMessengerSettingsHolderImpl at MultiMessenger level!
                platformMatrixMessengerSettingsHolderModule(),
                // TODO there should be a more clean way for I18n
                platformGetSystemLangModule(),
                module {
                    single<Languages> { DefaultLanguages }
                    single<I18n> { object : I18n(get(), get(), get()) {} }
                    single<FileSystem> { FakeFileSystem() }
                    single<RootPath> {
                        RootPath("/app".toPath())
                    }
                    single<MatrixMessengerConfiguration> {
                        MatrixMessengerConfiguration()
                    }
                },
                composeViewModule(),
                i18nViewModule(),
                tammyModule(),
            )
        }.koin
        di.get<MatrixMessengerSettingsHolder>().init()

        val mainViewModel =
            MockMainViewModel(LocaleUtil.localeFromString(LocaleUtil.getTestLocale().substringBefore("-")))
        runComposeUiTest {
            setContent {
                //val activity = LocalView.current.context as ComponentActivity
                //activity.enableEdgeToEdge()
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(horizontal = if (maxWidth - MAX_WIDTH > 0.dp) (maxWidth - MAX_WIDTH) / 2 else 0.dp)
                    ) {
                        val singlePane = this@BoxWithConstraints.maxWidth < SINGLE_PANE_THRESHOLD.dp
                        LaunchedEffect(singlePane) {
                            mainViewModel.setSinglePane(singlePane)
                        }

                        CompositionLocalProvider(
                            ImeVisible provides WindowInsets.isImeVisible,
                            Platform provides PlatformType.ANDROID,
                            IsFocused provides true,
                            LocalWindowScope provides null,
                            IsDebug provides false,
                            DI provides di,
                        ) {
                            MessengerTheme {
                                val isSinglePane = mainViewModel.isSinglePane.collectAsState().value
                                val showRoom = mainViewModel.showRoom.collectAsState().value
                                if (!showRoom || !isSinglePane) {
                                    Row(
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Box(modifier = Modifier.weight(if (isSinglePane) 1F else ROOM_LIST_WEIGHT)) {
                                            RoomListSwitch(mainViewModel)
                                        }
                                        if (!isSinglePane) {
                                            VerticalDivider(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .width(1.dp)
                                            )
                                            Box(modifier = Modifier.weight(ROOM_WEIGHT))
                                        }
                                    }
                                }
                                if (showRoom) {
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        if (!isSinglePane) {
                                            Box(modifier = Modifier.weight(ROOM_LIST_WEIGHT))
                                            VerticalDivider(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .width(1.dp)
                                            )
                                        }
                                        Box(modifier = Modifier.weight(if (isSinglePane) 1F else ROOM_WEIGHT)) {
                                            RoomSwitch(mainViewModel.roomRouterStack)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            mainViewModel.showRoom.value = false
            waitForIdle()
            Thread.sleep(300)
            if (mainViewModel.isSinglePane.value) {
                Screengrab.screenshot("room_list")
            }
            mainViewModel.showRoom.value = true
            waitForIdle()
            Thread.sleep(300)
            Screengrab.screenshot("room")
        }
    }
}

val selectedRoom = RoomId("!selected")

class MockMainViewModel(
    locale: Locale,
) : MainViewModel {
    override val isSinglePane: MutableStateFlow<Boolean> = MutableStateFlow(true)
    override val showRoom: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val selectedRoomId: MutableStateFlow<RoomId?> = MutableStateFlow(selectedRoom)
    override val isBackButtonVisible: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val avatarCutterRouterStack: Value<ChildStack<AvatarCutterRouter.Config, AvatarCutterRouter.Wrapper>> =
        MutableValue(ChildStack(AvatarCutterRouter.Config.None, AvatarCutterRouter.Wrapper.None))
    override val selfVerificationStack: Value<ChildStack<SelfVerificationRouter.Config, SelfVerificationRouter.Wrapper>> =
        MutableValue(ChildStack(SelfVerificationRouter.Config.None, SelfVerificationRouter.Wrapper.None))
    override val deviceVerificationRouterStack: Value<ChildStack<VerificationRouter.Config, VerificationRouter.Wrapper>> =
        MutableValue(ChildStack(VerificationRouter.Config.None, VerificationRouter.Wrapper.None))
    override val mediaRouterStack: Value<ChildStack<MediaRouter.Config, MediaRouter.Wrapper>> =
        MutableValue(ChildStack(MediaRouter.Config.None, MediaRouter.Wrapper.None))
    override val initialSyncStack: Value<ChildStack<InitialSyncRouter.Config, InitialSyncRouter.Wrapper>> =
        MutableValue(ChildStack(InitialSyncRouter.Config.None, InitialSyncRouter.Wrapper.None))

    override val roomListRouterStack: Value<ChildStack<RoomListRouter.Config, RoomListRouter.Wrapper>> =
        MutableValue(
            ChildStack(
                RoomListRouter.Config.RoomList,
                RoomListRouter.Wrapper.List(RoomListViewModelMock(locale))
            )
        )
    override val roomRouterStack: Value<ChildStack<RoomRouter.Config, RoomRouter.Wrapper>> =
        MutableValue(
            ChildStack(
                RoomRouter.Config.View(UserId("tammy", "server"), selectedRoom.full),
                RoomRouter.Wrapper.View(RoomViewModelMock(locale))
            )
        )

    override fun closeAccountsOverview() {}
    override fun closeDetailsAndShowList() {}
    override fun onOpenAvatarCutter(userId: UserId, file: FileDescriptor) {}
    override fun onOpenAvatarCutter(userId: UserId, selectedRoomId: RoomId, file: FileDescriptor) {}
    override fun onRoomSelected(userId: UserId, id: RoomId) {}
    override fun openMention(userId: UserId, messageMention: MessageMention) {}
    override fun openModal(
        type: OpenModalType,
        mxcUrl: String,
        encryptedFile: EncryptedFile?,
        fileName: String,
        userId: UserId
    ) {
    }

    override fun setSinglePane(isSinglePane: Boolean) {
        this.isSinglePane.value = isSinglePane
    }

    override fun start() {}
}

class RoomListViewModelMock(
    locale: Locale,
) : RoomListViewModel {
    override val accountViewModel: AccountViewModel = AccountViewModelMock()
    override val activeSpace: MutableStateFlow<RoomId?> = MutableStateFlow(null)
    override val allSyncError: StateFlow<Boolean> = MutableStateFlow(false)
    override val canCreateNewRoomWithAccount: StateFlow<Boolean> = MutableStateFlow(true)
    override val closeProfileNeeded: Boolean = false
    override val error: StateFlow<String?> = MutableStateFlow(null)
    override val errorType: StateFlow<ErrorType> = MutableStateFlow(ErrorType.JUST_DISMISS)
    override val initialSyncFinished: StateFlow<Boolean> = MutableStateFlow(true)
    override val searchTerm: MutableStateFlow<String> = MutableStateFlow("")
    override val selectedRoomId: StateFlow<RoomId?> = MutableStateFlow(selectedRoom)
    override val showSearch: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val showSpaces: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val spaces: StateFlow<List<SpaceViewModel>> = MutableStateFlow(emptyList())
    override val syncStateError: StateFlow<Map<UserId, Boolean>> = MutableStateFlow(emptyMap())
    override val unverifiedAccounts: StateFlow<List<UserId>> = MutableStateFlow(emptyList())

    override val sortedRoomListElementViewModels: StateFlow<List<RoomListElement>> = MutableStateFlow(
        listOf(
            RoomListElementMock(
                "Timmy",
                null,
                inviterUserInfo = UserInfoElement("Timmy", UserId("tammy", "server"), "T", null)
            ),
            RoomListElementMock(
                "Tommy", resource("user1.png"), "19:59",
                mapOf(
                    Locale.ENGLISH to "Great :-)",
                    Locale.GERMAN to "Super :-)",
                )[locale] ?: "???", unreadMessages = 1
            ),
            RoomListElementMock(
                mapOf(
                    Locale.ENGLISH to "Mommy",
                    Locale.GERMAN to "Mama",
                )[locale] ?: "???",
                resource("user2.png"), "17:44",
                mapOf(
                    Locale.ENGLISH to "Thank you honey.",
                    Locale.GERMAN to "Danke liebes.",
                )[locale] ?: "???"
            ),
            RoomListElementMock("Bob", resource("user3.png"), "17:10", "🥰", unreadMessages = 3),
            RoomListElementMock(
                "Frank", resource("user4.png"), "16:52",
                mapOf(
                    Locale.ENGLISH to "Then let's just try it out ☺️",
                    Locale.GERMAN to "Na dann einfach probieren ☺️",
                )[locale] ?: "???"
            ),
            RoomListElementMock(
                mapOf(
                    Locale.ENGLISH to "Board Games Night",
                    Locale.GERMAN to "Brettspielnacht",
                )[locale] ?: "???",
                resource("boardgame.png"),
                "16:44",
                mapOf(
                    Locale.ENGLISH to "You: Alright, see you next week at the latest!",
                    Locale.GERMAN to "Du: Alles klar, dann bis spätestens nächste Woche!",
                )[locale] ?: "???",
                isDirect = false,
            ),
            RoomListElementMock(
                mapOf(
                    Locale.ENGLISH to "Family",
                    Locale.GERMAN to "Familie",
                )[locale] ?: "???", resource("family.png"), "11:30",
                mapOf(
                    Locale.ENGLISH to "Michael: That was fun!",
                    Locale.GERMAN to "Michael: Das hat Spaß gemacht!",
                )[locale] ?: "???",
                isDirect = false
            ),
            RoomListElementMock("Luna", resource("user5.png"), "11:12", "Sent an image."),
            RoomListElementMock(
                mapOf(
                    Locale.ENGLISH to "Movie Maniacs",
                    Locale.GERMAN to "Filmfans",
                )[locale] ?: "???",
                resource("movie.png"),
                "11:11",
                mapOf(
                    Locale.ENGLISH to "Benedict: Best movie ever 👍",
                    Locale.GERMAN to "Benedict: Bester Film überhaupt 👍",
                )[locale] ?: "???",
                isDirect = false,
                isEncrypted = false,
                isPublic = true
            ),
            RoomListElementMock(
                "Lilly", resource("user6.png"), "07:16",
                mapOf(
                    Locale.ENGLISH to "Oh yeah. I understand...",
                    Locale.GERMAN to "Oh ja. Kann ich verstehen...",
                )[locale] ?: "???",
            ),
            RoomListElementMock(
                "Nina", resource("user7.png"), "12.06.2024",
                mapOf(
                    Locale.ENGLISH to "Until then 🎸",
                    Locale.GERMAN to "Bis dahin 🎸",
                )[locale] ?: "???",
            ),
            RoomListElementMock(
                "Bruno", resource("user8.png"), "12.06.2024",
                mapOf(
                    Locale.ENGLISH to "I like that idea 😀",
                    Locale.GERMAN to "Ich mag die Idee 😀",
                )[locale] ?: "???",
            ),
            RoomListElementMock(
                "Felicitas", resource("user11.png"), "13.06.2024",
                mapOf(
                    Locale.ENGLISH to "Have a nice week!",
                    Locale.GERMAN to "Schöne Woche noch!",
                )[locale] ?: "???",
            ),
        )
    )

    override fun closeProfile() {}
    override fun createNewRoom() {}
    override fun createNewRoomFor(userId: UserId) {}
    override fun errorDismiss() {}
    override fun openAccountsOverview() {}
    override fun selectRoom(roomId: RoomId) {}
    override fun sendLogs() {}
    override fun verifyAccount(userId: UserId) {}
}

class AccountViewModelMock : AccountViewModel {
    override val accounts: StateFlow<List<AccountInfo>> = MutableStateFlow(
        listOf(
            AccountInfo(
                userId = UserId("tammy", "server"),
                displayName = "Tammy",
                displayColor = null,
                initials = "T",
                avatar = resource("logo.png"),
            )
        )
    )
    override val activeAccount: StateFlow<UserId?> = MutableStateFlow(UserId("tammy", "server"))
    override val isSingleAccount: StateFlow<Boolean> = MutableStateFlow(true)

    override fun appInfo() {}
    override fun selectActiveAccount(userId: UserId?) {}
    override fun userSettings() {}
}

fun RoomListElementMock(
    roomName: String,
    roomImage: ByteArray? = null,
    time: String? = null,
    lastMessage: String? = null,
    isDirect: Boolean = true,
    isEncrypted: Boolean = true,
    isPublic: Boolean = false,
    inviterUserInfo: UserInfoElement? = null,
    unreadMessages: Int? = null,
    presence: Presence? = null,
): RoomListElement {
    val roomId = RoomId(Random.nextString(12))
    return RoomListElement(
        roomId = roomId, isDirect = isDirect, isInvite = inviterUserInfo != null,
        viewModel = RoomListElementViewModelMock(
            roomName = roomName,
            roomImage = roomImage,
            lastMessage = lastMessage,
            time = time,
            isDirect = isDirect,
            isEncrypted = isEncrypted,
            isPublic = isPublic,
            inviterUserInfo = inviterUserInfo,
            unreadMessages = unreadMessages,
            presence = presence
        )
    )
}

class RoomListElementViewModelMock(
    roomName: String,
    roomImage: ByteArray? = null,
    lastMessage: String? = null,
    time: String? = null,
    isDirect: Boolean = true,
    isEncrypted: Boolean = true,
    isPublic: Boolean = false,
    inviterUserInfo: UserInfoElement? = null,
    unreadMessages: Int? = null,
    presence: Presence? = null,
) : RoomListElementViewModel {
    override val account: UserId = UserId("alice", "server")
    override val accountColor: StateFlow<Long?> = MutableStateFlow(null)
    override val error: StateFlow<String?> = MutableStateFlow(null)
    override val inviterUserInfo: StateFlow<UserInfoElement?> = MutableStateFlow(inviterUserInfo)
    override val isDirect: StateFlow<Boolean?> = MutableStateFlow(isDirect)
    override val isEncrypted: StateFlow<Boolean?> = MutableStateFlow(isEncrypted)
    override val isInvite: StateFlow<Boolean?> = MutableStateFlow(inviterUserInfo != null)
    override val isPublic: StateFlow<Boolean?> = MutableStateFlow(isPublic)
    override val lastMessage: StateFlow<String?> = MutableStateFlow(lastMessage)
    override val presence: StateFlow<Presence?> = MutableStateFlow(presence)
    override val roomId: RoomId = RoomId(Random.nextString(12))
    override val roomImage: StateFlow<ByteArray?> = MutableStateFlow(roomImage)
    override val roomImageInitials: StateFlow<String?> = MutableStateFlow(inviterUserInfo?.initials ?: "")
    override val roomName: StateFlow<String?> = MutableStateFlow(roomName)
    override val time: StateFlow<String?> = MutableStateFlow(time)
    override val unreadMessages: StateFlow<String?> = MutableStateFlow(unreadMessages?.toString())

    override fun acceptInvitation() {}
    override fun clearError() {}
    override fun rejectInvitation() {}
    override fun rejectInvitationAndBlockInviter() {}
}

class RoomViewModelMock(locale: Locale) : RoomViewModel {
    override val isShowSettings: StateFlow<Boolean> = MutableStateFlow(false)
    override val isTwoPane: StateFlow<Boolean> = MutableStateFlow(false)
    override val settingsStack: Value<ChildStack<SettingsRouter.Config, SettingsRouter.Wrapper>> =
        MutableValue(ChildStack(SettingsRouter.Config.None, SettingsRouter.Wrapper.None))
    override val timelineStack: Value<ChildStack<TimelineRouter.Config, TimelineRouter.Wrapper>> =
        MutableValue(
            ChildStack(
                TimelineRouter.Config.View(selectedRoom.full),
                TimelineRouter.Wrapper.View(TimelineViewModelMock(locale))
            )
        )

    override fun onRoomBack() {}
    override fun setSinglePane(twoPane: Boolean) {}
    override fun showSettings() {}
}

class TimelineViewModelMock(
    locale: Locale,
) : TimelineViewModel {
    override val draggedFile: StateFlow<FileDescriptor?> = MutableStateFlow(null)
    override val error: StateFlow<String?> = MutableStateFlow(null)
    override val firstVisibleTimelineElement: MutableStateFlow<String?> = MutableStateFlow(null)
    override val inputAreaViewModel: InputAreaViewModel = InputAreaViewModelModelMock()
    override val isDirect: StateFlow<Boolean> = MutableStateFlow(false)
    override val lastVisibleTimelineElement: MutableStateFlow<String?> = MutableStateFlow(null)
    override val loadingBefore: StateFlow<Boolean> = MutableStateFlow(false)
    override val reportMessageStack: Value<ChildStack<ReportMessageRouter.Config, ReportMessageRouter.Wrapper>> =
        MutableValue(ChildStack(ReportMessageRouter.Config.None, ReportMessageRouter.Wrapper.None))
    override val roomHeaderViewModel: RoomHeaderViewModel = RoomHeaderViewModelMock(locale)
    override val sendAttachmentStack: Value<ChildStack<TimelineViewModel.Config, TimelineViewModel.Wrapper>> =
        MutableValue(ChildStack(TimelineViewModel.Config.None, TimelineViewModel.Wrapper.None))
    override val stickyDate: StateFlow<String?> = MutableStateFlow("13.06.2024")
    override val timelineElementHolderViewModels: StateFlow<List<BaseTimelineElementHolderViewModel>> =
        MutableStateFlow(
            listOf(
                TextMessageViewModelMock(
                    UserInfoElement("Tammy", UserId("tammy", "server")),
                    mapOf(
                        Locale.ENGLISH to "In one week it's that time again! We're having another little Board Games Night soon.",
                        Locale.GERMAN to "In einer Woche ist es wieder so weit! Wir veranstalten bald wieder eine kleine Brettspielnacht.",
                    )[locale] ?: "???",
                    formattedTime = "08:12",
                    isByMe = true,
                    showSender = false
                ) to mapOf(),
                TextMessageViewModelMock(
                    UserInfoElement("Henry", UserId("henry", "server")),
                    mapOf(
                        Locale.ENGLISH to "Oh right, I almost forgot about that.",
                        Locale.GERMAN to "Ach ja, das hätte ich fast vergessen.",
                    )[locale] ?: "???",
                    formattedTime = "08:37",
                    isByMe = false,
                    showSender = true
                ) to mapOf(),
                TextMessageViewModelMock(
                    UserInfoElement("Henry", UserId("henry", "server")),
                    mapOf(
                        Locale.ENGLISH to "Is everything already organized, or can I still help with something?",
                        Locale.GERMAN to "Ist schon alles organisiert oder kann ich noch etwas mithelfen?",
                    )[locale] ?: "???",
                    formattedTime = "08:38",
                    isByMe = false,
                    showSender = false
                ) to mapOf(),
                TextMessageViewModelMock(
                    UserInfoElement("Henry", UserId("henry", "server")),
                    mapOf(
                        Locale.ENGLISH to "Well, everyone is welcome to bring cake or other treats.",
                        Locale.GERMAN to "Nun, jeder kann Kuchen oder andere Leckereien mitzubringen.",
                    )[locale] ?: "???",
                    formattedTime = "08:43",
                    isByMe = false,
                    showSender = true
                ) to mapOf(),
                TextMessageViewModelMock(
                    UserInfoElement("Michael", UserId("michael", "server")),
                    mapOf(
                        Locale.ENGLISH to "Great, then I'll bring my famous strawberry cake again.",
                        Locale.GERMAN to "Gut, dann bringe ich wieder meinen berühmten Erdbeerkuchen mit.",
                    )[locale] ?: "???",
                    formattedTime = "08:31",
                    isByMe = false,
                    showSender = true,
                    referencedMessage = ReferencedMessage.ReferencedTextMessage(
                        UserInfoElement("Henry", UserId("henry", "server")),
                        mapOf(
                            Locale.ENGLISH to "Well, everyone is welcome to bring cake or other treats.",
                            Locale.GERMAN to "Nun, jeder kann Kuchen oder andere Leckereien mitzubringen.",
                        )[locale] ?: "???",
                    )
                ) to mapOf(),
                ImageMessageViewModelMock(
                    UserInfoElement("Michael", UserId("michael", "server")),
                    resource("strawberrycake.png"),
                    1024,
                    1024,
                    formattedTime = "08:50",
                    isByMe = false,
                    showSender = false
                ) to mapOf(),
                TextMessageViewModelMock(
                    UserInfoElement("Tina", UserId("tina", "server")),
                    mapOf(
                        Locale.ENGLISH to "😋\nDoes anyone have ideas on what we should play?",
                        Locale.GERMAN to "😋\nHat jemand eine Idee, was wir spielen können?",
                    )[locale] ?: "???",
                    formattedTime = "08:43",
                    isByMe = false,
                    showSender = true
                ) to mapOf(),
                TextMessageViewModelMock(
                    UserInfoElement("Tammy", UserId("tammy", "server")),
                    mapOf(
                        Locale.ENGLISH to "Oh, I have a lot of options here. But since we are quite a few people, some games won't work. " +
                                "I'll take a look and see what would fit well! I'm already excited!",
                        Locale.GERMAN to "Oh, ich habe hier eine Menge Möglichkeiten. Aber da wir ziemlich viele Leute sind, werden einige Spiele nicht funktionieren. " +
                                "Ich werde mal schauen, was gut passen würde! Ich bin schon ganz hibbelig!",
                    )[locale] ?: "???",
                    formattedTime = "08:49",
                    isByMe = true,
                    showSender = false
                ) to mapOf("👍" to 3, "🫠" to 1),
                TextMessageViewModelMock(
                    UserInfoElement("Michael", UserId("michael", "server")),
                    mapOf(
                        Locale.ENGLISH to "Oh, very cool. I'm really looking forward to it!",
                        Locale.GERMAN to "Oh, sehr cool. Ich freue mich schon sehr darauf!",
                    )[locale] ?: "???",
                    formattedTime = "08:50",
                    isByMe = false,
                    showSender = true
                ) to mapOf(),
                TextMessageViewModelMock(
                    UserInfoElement("Tammy", UserId("tammy", "server")),
                    mapOf(
                        Locale.ENGLISH to "Alright, see you next week at the latest!",
                        Locale.GERMAN to "Alles klar, dann bis spätestens nächste Woche!",
                    )[locale] ?: "???",
                    formattedTime = "09:01",
                    isByMe = true,
                    showSender = false
                ) to mapOf(),
            ).mapIndexed { index, vm -> TimelineElementHolderViewModelMock(index.toString(), vm.first, vm.second) }
        )
    override val scrollTo: Flow<String> = timelineElementHolderViewModels.map { it.last().key }
    override val windowIsFocused: MutableStateFlow<Boolean> = MutableStateFlow(true)

    override fun errorDismiss() {}
    override fun jumpToEndOfTimeline() {}
    override fun leaveRoom() {}
    override fun loadBefore() {}
}

class RoomHeaderViewModelMock(locale: Locale) : RoomHeaderViewModel {
    override val canBlockUser: StateFlow<Boolean> = MutableStateFlow(false)
    override val canUnblockUser: StateFlow<Boolean> = MutableStateFlow(false)
    override val canVerifyUser: StateFlow<Boolean> = MutableStateFlow(false)
    override val error: StateFlow<String?> = MutableStateFlow(null)
    override val isBackButtonVisible: StateFlow<Boolean> = MutableStateFlow(false)
    override val isUserBlocked: StateFlow<Boolean> = MutableStateFlow(false)
    override val roomHeaderInfo: StateFlow<RoomHeaderInfo> = MutableStateFlow(
        RoomHeaderInfo(
            roomName = mapOf(
                Locale.ENGLISH to "Board Games Night",
                Locale.GERMAN to "Brettspielnacht",
            )[locale] ?: "???",
            roomTopic = mapOf(
                Locale.ENGLISH to "Organizing our monthly Board Games Night",
                Locale.GERMAN to "Organisation unserer monatlichen Brettspielnacht",
            )[locale] ?: "???",
            roomImageInitials = "BG",
            roomImage = resource("boardgame.png"),
            presence = null,
            isEncrypted = true,
            isPublic = false
        )
    )
    override val userTrustLevel: StateFlow<UserTrustLevel?> = MutableStateFlow(null)
    override val usersTyping: StateFlow<String?> = MutableStateFlow(null)

    override fun blockUser() {}
    override fun goBack() {}
    override fun showRoomSettings() {}
    override fun unblockUser() {}
    override fun verifyUser() {}
}

class InputAreaViewModelModelMock : InputAreaViewModel {
    override val currentCursorPosition: MutableStateFlow<Int?> = MutableStateFlow(24)
    override val hasShownAttachmentSelectDialog: SharedFlow<Boolean> = MutableSharedFlow()
    override val isAllowedToSendMessages: StateFlow<Boolean> = MutableStateFlow(true)
    override val isEdit: StateFlow<Boolean> = MutableStateFlow(false)
    override val isReplyTo: StateFlow<Boolean> = MutableStateFlow(false)
    override val isSendEnabled: StateFlow<Boolean> = MutableStateFlow(true)
    override val listOfMentions: StateFlow<List<Username>> = MutableStateFlow(emptyList())
    override val listOfMentionsLoading: StateFlow<Boolean?> = MutableStateFlow(null)
    override val message: MutableStateFlow<String> = MutableStateFlow("Don't forget the games 😉")
    override val replyToViewModel: StateFlow<ReplyToViewModel?> = MutableStateFlow(null)
    override val shouldFocus: StateFlow<String?> = MutableStateFlow(null)
    override val showAttachmentSelectDialog: StateFlow<Boolean> = MutableStateFlow(false)

    override fun addNewlineToMessage() {}
    override fun addToMessage(additional: String) {}
    override fun cancelEdit() {}
    override fun cancelReplyTo() {}
    override fun closeAttachmentDialog() {}
    override fun editMessage(eventId: EventId) {}
    override fun onAttachmentFileSelect(file: FileDescriptor) {}
    override fun replyToMessage(eventId: EventId) {}
    override fun selectAttachment() {}
    override fun selectMention(username: Username) {}
    override fun sendMessage() {}
}

class TimelineElementHolderViewModelMock(
    override val key: String,
    timelineElementViewModel: BaseTimelineElementViewModel,
    reactions: Map<String, Int> = mapOf(),
) : TimelineElementHolderViewModel {
    override val eventId: EventId = EventId(key)
    override val canBeEdited: StateFlow<Boolean> = MutableStateFlow(false)
    override val canBeRedacted: StateFlow<Boolean> = MutableStateFlow(false)
    override val canBeRepliedTo: StateFlow<Boolean> = MutableStateFlow(false)
    override val canBeReported: StateFlow<Boolean> = MutableStateFlow(false)
    override val highlight: StateFlow<Boolean> = MutableStateFlow(false)
    override val isDirect: StateFlow<Boolean> = MutableStateFlow(false)
    override val isRead: StateFlow<Boolean> = MutableStateFlow(
        when (timelineElementViewModel) {
            is TextMessageViewModelMock -> timelineElementViewModel.isByMe
            is ImageMessageViewModelMock -> timelineElementViewModel.isByMe
            else -> false
        }
    )
    override val isReplaced: StateFlow<Boolean> = MutableStateFlow(false)
    override val redactionError: StateFlow<String?> = MutableStateFlow(null)
    override val redactionInProgress: StateFlow<Boolean> = MutableStateFlow(false)
    override val shouldShowUnreadMarkerFlow: StateFlow<Boolean> = MutableStateFlow(false)
    override val showLoadingIndicatorAfter: StateFlow<Boolean> = MutableStateFlow(false)
    override val showLoadingIndicatorBefore: StateFlow<Boolean> = MutableStateFlow(false)
    override val timelineElementViewModel: StateFlow<BaseTimelineElementViewModel?> =
        MutableStateFlow(timelineElementViewModel)
    override val reactionsOpen: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val reactions: StateFlow<Map<String, Set<TimelineElementHolderViewModel.ReactionEvent>>> =
        MutableStateFlow(
            reactions.mapValues { (_, number) ->
                buildSet {
                    repeat(number) {
                        add(
                            TimelineElementHolderViewModel.ReactionEvent(
                                EventId(number.toString()),
                                UserInfoElement(number.toString(), UserId(number.toString(), "server")),
                                false,
                                Clock.System.now()
                            )
                        )
                    }
                }
            }
        )

    override fun edit() {}
    override fun endEdit() {}
    override fun endReplyTo() {}
    override suspend fun isReadBy(): String = ""
    override fun redact() {}
    override fun replyTo() {}
    override fun reportTo() {}
    override fun addReaction(reaction: String) {}
    override fun removeReaction(reaction: TimelineElementHolderViewModel.ReactionEvent) {}
}

class TextMessageViewModelMock(
    sender: UserInfoElement,
    override val message: String,
    override val formattedTime: String?,
    override val isByMe: Boolean = false,
    showSender: Boolean = true,
    override val showChatBubbleEdge: Boolean = true,
    override val showBigGap: Boolean = false,
    referencedMessage: ReferencedMessage? = null,
) : TextMessageViewModel {
    override val fallbackMessage: String = message
    override val formattedBody: String? = null
    override val formattedDate: String = ""
    override val invitation: StateFlow<String?> = MutableStateFlow(null)
    override val mentionsInFormattedBody: Map<IntRange, StateFlow<MessageMention?>>? = mapOf()
    override val mentionsInMessage: Map<IntRange, StateFlow<MessageMention?>> = mapOf()
    override val referencedMessage: StateFlow<ReferencedMessage?> = MutableStateFlow(referencedMessage)
    override val sender: StateFlow<UserInfoElement> = MutableStateFlow(sender)
    override val showDateAbove: Boolean = false
    override val showSender: StateFlow<Boolean> = MutableStateFlow(showSender)

    override fun openMention(messageMention: MessageMention) {}
}

class ImageMessageViewModelMock(
    sender: UserInfoElement,
    thumbnail: ByteArray?,
    override val height: Int,
    override val width: Int,
    override val formattedTime: String?,
    override val isByMe: Boolean = false,
    showSender: Boolean = true,
    override val showChatBubbleEdge: Boolean = true,
    override val showBigGap: Boolean = false,
) : ImageMessageViewModel {
    override val thumbnail: StateFlow<ByteArray?> = MutableStateFlow(thumbnail)
    override val downloadError: MutableStateFlow<String?> = MutableStateFlow(null)
    override val downloadProgress: StateFlow<FileTransferProgressElement?> = MutableStateFlow(null)
    override val downloadSuccessful: StateFlow<Boolean?> = MutableStateFlow(true)
    override val fileMimeType: String? = null
    override val fileName: String = "image"
    override val fileSize: Long? = null
    override val formattedDate: String = ""
    override val invitation: StateFlow<String?> = MutableStateFlow(null)
    override val saveFileDialogOpen: StateFlow<Boolean> = MutableStateFlow(false)
    override val sender: StateFlow<UserInfoElement> = MutableStateFlow(sender)
    override val showDateAbove: Boolean = false
    override val showSender: StateFlow<Boolean> = MutableStateFlow(showSender)
    override val uploadProgress: StateFlow<FileTransferProgressElement?> = MutableStateFlow(null)

    override fun cancelDownload() {}
    override fun cancelThumbnailDownload() {}
    override fun closeSaveFileDialog() {}
    override fun downloadFile(onFile: suspend (ByteArrayFlow) -> Unit) {}
    override fun getHeight(maxWidth: Float): Int {
        return 0
    }

    override fun getMaxHeight(): Int {
        return 500
    }

    override fun getWidth(maxWidth: Float, possibleHeight: Float): Int {
        return 0
    }

    override fun openImage() {}
    override fun openSaveFileDialog() {}
}

fun resource(path: String): ByteArray = requireNotNull(object {}.javaClass.classLoader?.getResource(path)?.readBytes())