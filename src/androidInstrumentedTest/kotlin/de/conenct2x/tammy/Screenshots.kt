package de.conenct2x.tammy

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.tooling.preview.Preview
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import de.connect2x.trixnity.messenger.compose.view.*
import de.connect2x.trixnity.messenger.compose.view.root.Main
import de.connect2x.trixnity.messenger.compose.view.theme.IsFocusHighlighting
import de.connect2x.trixnity.messenger.compose.view.theme.MessengerTheme
import de.connect2x.trixnity.messenger.compose.view.theme.components
import de.connect2x.trixnity.messenger.compose.view.theme.components.ThemedSurface
import de.connect2x.tammy.tammyThemeModule
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
import de.connect2x.trixnity.messenger.util.UriCaller
import de.connect2x.trixnity.messenger.util.html.HtmlNode
import de.connect2x.trixnity.messenger.viewmodel.*
import de.connect2x.trixnity.messenger.viewmodel.initialsync.InitialSyncRouter
import de.connect2x.trixnity.messenger.viewmodel.room.RoomRouter
import de.connect2x.trixnity.messenger.viewmodel.room.RoomViewModel
import de.connect2x.trixnity.messenger.viewmodel.room.settings.ExtrasRouter
import de.connect2x.trixnity.messenger.viewmodel.room.timeline.*
import de.connect2x.trixnity.messenger.viewmodel.room.timeline.elements.*
import de.connect2x.trixnity.messenger.viewmodel.room.timeline.elements.message.RoomMessageTimelineElementViewModel
import de.connect2x.trixnity.messenger.viewmodel.roomlist.AccountViewModel
import de.connect2x.trixnity.messenger.viewmodel.roomlist.RoomListElementViewModel
import de.connect2x.trixnity.messenger.viewmodel.roomlist.RoomListRouter
import de.connect2x.trixnity.messenger.viewmodel.roomlist.RoomListViewModel
import de.connect2x.trixnity.messenger.viewmodel.settings.AccountSetupRouter
import de.connect2x.trixnity.messenger.viewmodel.settings.AvatarCutterRouter
import de.connect2x.trixnity.messenger.viewmodel.sharing.SharingRouter
import de.connect2x.trixnity.messenger.viewmodel.util.ErrorType
import de.connect2x.trixnity.messenger.viewmodel.util.EventReaction
import de.connect2x.trixnity.messenger.viewmodel.util.EventReactions
import de.connect2x.trixnity.messenger.viewmodel.verification.SelfVerificationRouter
import de.connect2x.trixnity.messenger.viewmodel.verification.VerificationRouter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import net.folivo.trixnity.client.media.PlatformMedia
import net.folivo.trixnity.core.MSC2448
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.m.Presence
import net.folivo.trixnity.crypto.key.UserTrustLevel
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
        runComposeUiTest {
            val isRoomShown = MutableStateFlow(false)
            setContent {
                ScreenshotView(
                    LocaleUtil.localeFromString(
                        (LocaleUtil.getTestLocale() ?: "en-US").substringBefore("-")
                    ), isRoomShown
                )
            }
            isRoomShown.value = false
            waitForIdle()
            Thread.sleep(5000)
            Screengrab.screenshot("room_list")
            isRoomShown.value = true
            waitForIdle()
            Thread.sleep(5000)
            Screengrab.screenshot("room")
        }
    }
}

@Composable
fun ScreenshotView(locale: Locale = Locale.ENGLISH, isRoomShown: MutableStateFlow<Boolean> = MutableStateFlow(false)) {
    var initFinished by remember { mutableStateOf(true) }

    val di = remember {
        koinApplication {
            modules(
                // TODO this needs to be removed and fixed, as there is no MatrixMessengerSettingsHolderImpl at MultiMessenger level!
                platformMatrixMessengerSettingsHolderModule(),
                // TODO there should be a more clean way for I18n
                platformGetSystemLangModule(),
                module {
                    single<Languages> { DefaultLanguages }
                    single<I18n> { object : I18n(get(), get(), get(), get()) {} }
                    single<TimeZone> { TimeZone.UTC }
                    single<FileSystem> { FakeFileSystem() }
                    single<RootPath> {
                        RootPath("/app".toPath())
                    }
                    single<MatrixMessengerConfiguration> {
                        MatrixMessengerConfiguration()
                    }
                    single<UriCaller> {
                        UriCaller { _, _ -> }
                    }
                },
                composeViewModule(MatrixMessengerConfiguration()),
                tammyThemeModule(),
            )
        }.koin
    }
    LaunchedEffect(Unit) {
        di.get<MatrixMessengerSettingsHolder>().init()
        initFinished = true
    }

    if (initFinished) {
        val mainViewModel = remember { MockMainViewModel(locale) }
        LaunchedEffect(isRoomShown) {
            isRoomShown.collect { mainViewModel.isRoomShown.value = it }
        }
        CompositionLocalProvider(
            Platform provides PlatformType.ANDROID,
            DI provides di,
            IsFocused provides true,
            IsFocusHighlighting provides false,
        ) {
            MessengerTheme {
                ThemedSurface(
                    style = MaterialTheme.components.background,
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                ) {
                    Main(mainViewModel)
                }
            }
        }
    } else Text("loading")
}

@Preview
@Composable
fun ScreenshotViewPreview() {
    ScreenshotView()
}

val selectedRoom = RoomId("!selected")

class MockMainViewModel(
    locale: Locale,
) : MainViewModel {
    override val selectedRoomId: MutableStateFlow<RoomId?> = MutableStateFlow(selectedRoom)
    override val isRoomShown: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val avatarCutterRouterStack: Value<ChildStack<AvatarCutterRouter.Config, AvatarCutterRouter.Wrapper>> =
        MutableValue(ChildStack(AvatarCutterRouter.Config.None, AvatarCutterRouter.Wrapper.None))
    override val selfVerificationStack: Value<ChildStack<SelfVerificationRouter.Config, SelfVerificationRouter.Wrapper>> =
        MutableValue(ChildStack(SelfVerificationRouter.Config.None, SelfVerificationRouter.Wrapper.None))
    override val deviceVerificationRouterStack: Value<ChildStack<VerificationRouter.Config, VerificationRouter.Wrapper>> =
        MutableValue(ChildStack(VerificationRouter.Config.None, VerificationRouter.Wrapper.None))
    override val initialSyncStack: Value<ChildStack<InitialSyncRouter.Config, InitialSyncRouter.Wrapper>> =
        MutableValue(ChildStack(InitialSyncRouter.Config.None, InitialSyncRouter.Wrapper.None))
    override val accountSetupRouterStack: Value<ChildStack<AccountSetupRouter.Config, AccountSetupRouter.Wrapper>> =
        MutableValue(ChildStack(AccountSetupRouter.Config.None, AccountSetupRouter.Wrapper.None))
    override val sharingStack: Value<ChildStack<SharingRouter.Config, SharingRouter.Wrapper>> =
        MutableValue(ChildStack(SharingRouter.Config.None, SharingRouter.Wrapper.None))

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
    override fun openMention(userId: UserId, timelineElementMention: TimelineElementMention) {}
    override fun openSelfVerification(userId: UserId) {}
    override fun start() {}
}

class RoomListViewModelMock(
    locale: Locale,
) : RoomListViewModel {
    override val accountViewModel: AccountViewModel = AccountViewModelMock()
    override val allSyncError: StateFlow<Boolean> = MutableStateFlow(false)
    override val canCreateNewRoomWithAccount: StateFlow<Boolean> = MutableStateFlow(true)
    override val closeProfileNeeded: Boolean = false
    override val error: StateFlow<String?> = MutableStateFlow(null)
    override val errorType: StateFlow<ErrorType> = MutableStateFlow(ErrorType.JUST_DISMISS)
    override val initialSyncFinished: StateFlow<Boolean> = MutableStateFlow(true)
    override val syncStates: StateFlow<RoomListViewModel.UserSyncStates> =
        MutableStateFlow(RoomListViewModel.UserSyncStates(setOf(), setOf()))
    override val searchTerm: TextFieldViewModel = TextFieldViewModelImpl(100)
    override val selectedRoomId: StateFlow<RoomId?> = MutableStateFlow(selectedRoom)
    override val showSearch: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val searchResultsEmpty: StateFlow<Boolean> = MutableStateFlow(false)
    override val syncStateError: StateFlow<Map<UserId, Boolean>> = MutableStateFlow(emptyMap())
    override val unverifiedAccounts: StateFlow<List<UserId>> = MutableStateFlow(emptyList())

    override val elements: StateFlow<List<RoomListElementViewModel>> = MutableStateFlow(
        listOf(
            RoomListElementViewModelMock(
                roomName = "Timmy",
                roomImage = null,
                inviterUserInfo = UserInfoElement(UserId("tammy", "server"), "Timmy", "T", null)
            ),
            RoomListElementViewModelMock(
                roomName = "Tommy",
                roomImage = resource("user1.png"),
                time = "19:59",
                lastMessage = mapOf(
                    Locale.ENGLISH to "Great :-)",
                    Locale.GERMAN to "Super :-)",
                )[locale] ?: "???", unreadMessages = 1
            ),
            RoomListElementViewModelMock(
                roomName = mapOf(
                    Locale.ENGLISH to "Mommy",
                    Locale.GERMAN to "Mama",
                )[locale] ?: "???",
                roomImage = resource("user2.png"),
                time = "17:44",
                lastMessage = mapOf(
                    Locale.ENGLISH to "Thank you honey.",
                    Locale.GERMAN to "Danke liebes.",
                )[locale] ?: "???"
            ),
            RoomListElementViewModelMock(
                roomName = "Bob",
                roomImage = resource("user3.png"),
                time = "17:10",
                lastMessage = "🥰",
                unreadMessages = 3
            ),
            RoomListElementViewModelMock(
                roomName = "Frank",
                roomImage = resource("user4.png"),
                time = "16:52",
                lastMessage = mapOf(
                    Locale.ENGLISH to "Then let's just try it out ☺️",
                    Locale.GERMAN to "Na dann einfach probieren ☺️",
                )[locale] ?: "???"
            ),
            RoomListElementViewModelMock(
                roomName = mapOf(
                    Locale.ENGLISH to "Board Games Night",
                    Locale.GERMAN to "Brettspielnacht",
                )[locale] ?: "???",
                roomImage = resource("boardgame.png"),
                time = "16:44",
                lastMessage = mapOf(
                    Locale.ENGLISH to "You: Alright, see you next week at the latest!",
                    Locale.GERMAN to "Du: Alles klar, dann bis spätestens nächste Woche!",
                )[locale] ?: "???",
                isDirect = false,
            ),
            RoomListElementViewModelMock(
                roomName = mapOf(
                    Locale.ENGLISH to "Family",
                    Locale.GERMAN to "Familie",
                )[locale] ?: "???",
                roomImage = resource("family.png"),
                time = "11:30",
                lastMessage = mapOf(
                    Locale.ENGLISH to "Michael: That was fun!",
                    Locale.GERMAN to "Michael: Das hat Spaß gemacht!",
                )[locale] ?: "???",
                isDirect = false
            ),
            RoomListElementViewModelMock(
                roomName = "Luna",
                roomImage = resource("user5.png"),
                time = "11:12",
                lastMessage = "Sent an image."
            ),
            RoomListElementViewModelMock(
                roomName = mapOf(
                    Locale.ENGLISH to "Movie Maniacs",
                    Locale.GERMAN to "Filmfans",
                )[locale] ?: "???",
                roomImage = resource("movie.png"),
                time = "11:11",
                lastMessage = mapOf(
                    Locale.ENGLISH to "Benedict: Best movie ever 👍",
                    Locale.GERMAN to "Benedict: Bester Film überhaupt 👍",
                )[locale] ?: "???",
                isDirect = false,
                isEncrypted = false,
                isPublic = true
            ),
            RoomListElementViewModelMock(
                roomName = "Lilly",
                roomImage = resource("user6.png"),
                time = "07:16",
                lastMessage = mapOf(
                    Locale.ENGLISH to "Oh yeah. I understand...",
                    Locale.GERMAN to "Oh ja. Kann ich verstehen...",
                )[locale] ?: "???",
            ),
            RoomListElementViewModelMock(
                roomName = "Nina",
                roomImage = resource("user7.png"),
                time = "12.06.2024",
                lastMessage = mapOf(
                    Locale.ENGLISH to "Until then 🎸",
                    Locale.GERMAN to "Bis dahin 🎸",
                )[locale] ?: "???",
            ),
            RoomListElementViewModelMock(
                roomName = "Bruno",
                roomImage = resource("user8.png"),
                time = "12.06.2024",
                lastMessage = mapOf(
                    Locale.ENGLISH to "I like that idea 😀",
                    Locale.GERMAN to "Ich mag die Idee 😀",
                )[locale] ?: "???",
            ),
            RoomListElementViewModelMock(
                roomName = "Felicitas",
                roomImage = resource("user11.png"),
                time = "13.06.2024",
                lastMessage = mapOf(
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

    override fun selectActiveAccount(userId: UserId?) {}
    override fun openUserSettings() {}
    override fun openUserProfile() {}
    override fun openAppInfo() {}
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
    override val rejectInvitationInProgress: StateFlow<Boolean> = MutableStateFlow(false)

    override val error: StateFlow<String?> = MutableStateFlow(null)
    override val inviterUserInfo: StateFlow<UserInfoElement?> = MutableStateFlow(inviterUserInfo)
    override val isDirect: StateFlow<Boolean?> = MutableStateFlow(isDirect)
    override val isEncrypted: StateFlow<Boolean?> = MutableStateFlow(isEncrypted)
    override val isInvite: StateFlow<Boolean?> = MutableStateFlow(inviterUserInfo != null)
    override val isLeave: StateFlow<Boolean?> = MutableStateFlow(false)
    override val isKnock: StateFlow<Boolean?> = MutableStateFlow(false)
    override val isPublic: StateFlow<Boolean?> = MutableStateFlow(isPublic)
    override val lastMessage: StateFlow<String?> = MutableStateFlow(lastMessage)
    override val usersTyping: StateFlow<String?> = MutableStateFlow(null)
    override val presence: StateFlow<Presence?> = MutableStateFlow(presence)
    override val roomId: RoomId = RoomId(Random.nextString(12))
    override val isLoaded: StateFlow<Boolean> = MutableStateFlow(true)
    override val roomImage: StateFlow<ByteArray?> = MutableStateFlow(roomImage)
    override val roomImageInitials: StateFlow<String?> = MutableStateFlow(inviterUserInfo?.initials ?: "")
    override val roomName: StateFlow<String?> = MutableStateFlow(roomName)
    override val time: StateFlow<String?> = MutableStateFlow(time)
    override val unreadMessages: StateFlow<String?> = MutableStateFlow(unreadMessages?.toString())

    override fun acceptInvitation() {}
    override fun clearError() {}
    override fun rejectInvitation() {}
    override fun rejectInvitationAndBlockInviter() {}
    override fun unknock() {}
    override fun forgetRoom() {}
}

class RoomViewModelMock(locale: Locale) : RoomViewModel {
    override val timelineStack: Value<ChildStack<TimelineRouter.Config, TimelineRouter.Wrapper>> =
        MutableValue(
            ChildStack(
                TimelineRouter.Config.View(selectedRoom.full),
                TimelineRouter.Wrapper.View(TimelineViewModelMock(locale))
            )
        )
    override val extrasStack: Value<ChildStack<ExtrasRouter.Config, ExtrasRouter.Wrapper>> =
        MutableValue(ChildStack(ExtrasRouter.Config.None, ExtrasRouter.Wrapper.None))
    override val isShown: StateFlow<Boolean> = MutableStateFlow(false)
    override val isRoomSettingsShown: StateFlow<Boolean> = MutableStateFlow(false)

    override fun closeRoom() {}
    override fun openRoomSettings() {}
    override fun openUserProfile(userId: UserId) {}
    override fun openTimelineElementMetadata(eventId: EventId) {}
}

class TimelineViewModelMock(
    locale: Locale,
) : TimelineViewModel {
    override val draggedFile: StateFlow<FileDescriptor?> = MutableStateFlow(null)
    override val unreadCount: StateFlow<String?> = MutableStateFlow(null)
    override val error: StateFlow<String?> = MutableStateFlow(null)
    override val inputAreaViewModel: InputAreaViewModel = InputAreaViewModelModelMock()
    override val isDirect: StateFlow<Boolean> = MutableStateFlow(false)
    override val reportMessageStack: Value<ChildStack<ReportMessageRouter.Config, ReportMessageRouter.Wrapper>> =
        MutableValue(ChildStack(ReportMessageRouter.Config.None, ReportMessageRouter.Wrapper.None))
    override val roomHeaderViewModel: RoomHeaderViewModel = RoomHeaderViewModelMock(locale)
    override val sendAttachmentStack: Value<ChildStack<TimelineViewModel.Config, TimelineViewModel.Wrapper>> =
        MutableValue(ChildStack(TimelineViewModel.Config.None, TimelineViewModel.Wrapper.None))
    override val elements: StateFlow<List<BaseTimelineElementHolderViewModel>> =
        MutableStateFlow(
            listOf(
                TimelineElementHolderViewModelMock(
                    sender = UserInfoElement(UserId("tammy", "server"), "Tammy", "T"),
                    element = TextMessageViewModelMock(
                        mapOf(
                            Locale.ENGLISH to "In one week it's that time again! We're having another little Board Games Night soon.",
                            Locale.GERMAN to "In einer Woche ist es wieder so weit! Wir veranstalten bald wieder eine kleine Brettspielnacht.",
                        )[locale] ?: "???"
                    ),
                    formattedTime = "08:12",
                    isByMe = true,
                    showSender = false,
                    isFirstInUserSequence = true,
                ),
                TimelineElementHolderViewModelMock(
                    sender = UserInfoElement(UserId("henry", "server"), "Henry", "H"),
                    element = TextMessageViewModelMock(
                        mapOf(
                            Locale.ENGLISH to "Oh right, I almost forgot about that.",
                            Locale.GERMAN to "Ach ja, das hätte ich fast vergessen.",
                        )[locale] ?: "???"
                    ),
                    formattedTime = "08:37",
                    isByMe = false,
                    showSender = true,
                    isFirstInUserSequence = true,
                ),
                TimelineElementHolderViewModelMock(
                    sender = UserInfoElement(UserId("henry", "server"), "Henry", "H"),
                    element = TextMessageViewModelMock(
                        mapOf(
                            Locale.ENGLISH to "Is everything already organized, or can I still help with something?",
                            Locale.GERMAN to "Ist schon alles organisiert oder kann ich noch etwas mithelfen?",
                        )[locale] ?: "???"
                    ),
                    formattedTime = "08:38",
                    isByMe = false,
                    showSender = false,
                    isFirstInUserSequence = false,
                ),
                TimelineElementHolderViewModelMock(
                    sender = UserInfoElement(UserId("henry", "server"), "Henry", "H"),
                    element = TextMessageViewModelMock(
                        mapOf(
                            Locale.ENGLISH to "Well, everyone is welcome to bring cake or other treats.",
                            Locale.GERMAN to "Nun, jeder kann Kuchen oder andere Leckereien mitzubringen.",
                        )[locale] ?: "???"
                    ),
                    formattedTime = "08:43",
                    isByMe = false,
                    showSender = false,
                    isFirstInUserSequence = false,
                ),
                TimelineElementHolderViewModelMock(
                    sender = UserInfoElement(UserId("michael", "server"), "Michael", "M"),
                    element = TextMessageViewModelMock(
                        mapOf(
                            Locale.ENGLISH to "Great, then I'll bring my famous strawberry cake again.",
                            Locale.GERMAN to "Gut, dann bringe ich wieder meinen berühmten Erdbeerkuchen mit.",
                        )[locale] ?: "???"
                    ),
                    formattedTime = "08:31",
                    isByMe = false,
                    showSender = true,
                    isFirstInUserSequence = true,
                    repliedElement = TimelineElementHolderViewModelMock(
                        sender = UserInfoElement(UserId("henry", "server"), "Henry", "H"),
                        element = TextMessageViewModelMock(
                            mapOf(
                                Locale.ENGLISH to "Well, everyone is welcome to bring cake or other treats.",
                                Locale.GERMAN to "Nun, jeder kann Kuchen oder andere Leckereien mitzubringen.",
                            )[locale] ?: "???"
                        ),
                        formattedTime = "08:43",
                        isByMe = false,
                        showSender = true,
                        isFirstInUserSequence = true,
                    )
                ),
                TimelineElementHolderViewModelMock(
                    sender = UserInfoElement(UserId("michael", "server"), "Michael", "M"),
                    element = ImageMessageViewModelMock(
                        resource("strawberrycake.png"), caption = mapOf(
                            Locale.ENGLISH to "Great, then I'll bring my famous strawberry cake again.",
                            Locale.GERMAN to "Gut, dann bringe ich wieder meinen berühmten Erdbeerkuchen mit.",
                        )[locale] ?: "???", 1024, 1024
                    ),
                    formattedTime = "08:50",
                    isByMe = false,
                    showSender = false,
                    isFirstInUserSequence = false,
                    repliedElement = TimelineElementHolderViewModelMock(
                        sender = UserInfoElement(UserId("henry", "server"), "Henry", "H"),
                        element = TextMessageViewModelMock(
                            mapOf(
                                Locale.ENGLISH to "Well, everyone is welcome to bring cake or other treats.",
                                Locale.GERMAN to "Nun, jeder kann Kuchen oder andere Leckereien mitzubringen.",
                            )[locale] ?: "???"
                        ),
                        formattedTime = "08:43",
                        isByMe = false,
                        showSender = true,
                        isFirstInUserSequence = true,
                    )
                ),
                TimelineElementHolderViewModelMock(
                    sender = UserInfoElement(UserId("tina", "server"), "Tina", "T"),
                    element = TextMessageViewModelMock(
                        mapOf(
                            Locale.ENGLISH to "😋\nDoes anyone have ideas on what we should play?",
                            Locale.GERMAN to "😋\nHat jemand eine Idee, was wir spielen können?",
                        )[locale] ?: "???"
                    ),
                    formattedTime = "08:43",
                    isByMe = false,
                    showSender = true,
                    isFirstInUserSequence = true,
                ),
                TimelineElementHolderViewModelMock(
                    sender = UserInfoElement(UserId("tammy", "server"), "Tammy", "T"),
                    element = TextMessageViewModelMock(
                        mapOf(
                            Locale.ENGLISH to "Oh, I have a lot of options here. But since we are quite a few people, some games won't work. " +
                                    "I'll take a look and see what would fit well! I'm already excited!",
                            Locale.GERMAN to "Oh, ich habe hier eine Menge Möglichkeiten. Aber da wir ziemlich viele Leute sind, werden einige Spiele nicht funktionieren. " +
                                    "Ich werde mal schauen, was gut passen würde! Ich bin schon ganz hibbelig!",
                        )[locale] ?: "???"
                    ),
                    formattedTime = "08:49",
                    isByMe = true,
                    showSender = false,
                    reactions = mapOf("👍" to 3, "🫠" to 1),
                    isFirstInUserSequence = true,
                ),
                TimelineElementHolderViewModelMock(
                    sender = UserInfoElement(UserId("michael", "server"), "Michael", "M"),
                    element = TextMessageViewModelMock(
                        mapOf(
                            Locale.ENGLISH to "Oh, very cool. I'm really looking forward to it!",
                            Locale.GERMAN to "Oh, sehr cool. Ich freue mich schon sehr darauf!",
                        )[locale] ?: "???"
                    ),
                    formattedTime = "08:50",
                    isByMe = false,
                    showSender = true,
                    isFirstInUserSequence = true,
                ),
                TimelineElementHolderViewModelMock(
                    sender = UserInfoElement(UserId("tammy", "server"), "Tammy", "T"),
                    element = TextMessageViewModelMock(
                        mapOf(
                            Locale.ENGLISH to "Alright, see you next week at the latest!",
                            Locale.GERMAN to "Alles klar, dann bis spätestens nächste Woche!",
                        )[locale] ?: "???"
                    ),
                    formattedTime = "09:01",
                    isByMe = true,
                    showSender = false,
                    isFirstInUserSequence = true,
                ),
            )
        )
    override val viewState: MutableStateFlow<TimelineViewModel.ViewState?> = MutableStateFlow(null)
    override val scrollTo: Flow<String> = elements.map { it.last().key }

    override fun errorDismiss() {}
    override fun jumpToEndOfTimeline() {}
    override suspend fun loadBefore() {}
    override fun leaveRoom() {}
    override suspend fun loadAfter() {}
    override suspend fun dropBefore(key: String) {}
    override suspend fun dropAfter(key: String) {}
    override suspend fun markAsRead(key: String) {}
}

class RoomHeaderViewModelMock(locale: Locale) : RoomHeaderViewModel {
    override val canBlockUser: StateFlow<Boolean> = MutableStateFlow(false)
    override val canUnblockUser: StateFlow<Boolean> = MutableStateFlow(false)
    override val canVerifyUser: StateFlow<Boolean> = MutableStateFlow(false)
    override val knockingMembersCount: StateFlow<Int> = MutableStateFlow(0)
    override val error: StateFlow<String?> = MutableStateFlow(null)
    override val isUserBlocked: StateFlow<Boolean> = MutableStateFlow(false)
    override val isDirectChat: StateFlow<Boolean> = MutableStateFlow(false)
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
            isPublic = false,
            isLeave = false,
        )
    )
    override val userTrustLevel: StateFlow<UserTrustLevel?> = MutableStateFlow(null)
    override val usersTyping: StateFlow<String?> = MutableStateFlow(null)

    override fun blockUser() {}
    override fun unblockUser() {}
    override fun verifyUser() {}
    override fun openRoomSettings() {}
    override fun openUserProfile() {}
    override fun back() {}
}

class InputAreaViewModelModelMock : InputAreaViewModel {
    override val hasShownAttachmentSelectDialog: SharedFlow<Boolean> = MutableSharedFlow()
    override val isReplace: StateFlow<Boolean> = MutableStateFlow(false)
    override val isReply: StateFlow<Boolean> = MutableStateFlow(false)
    override val repliedElement: StateFlow<TimelineElementHolderViewModel?> = MutableStateFlow(null)
    override val isAllowedToSendMessages: StateFlow<Boolean> = MutableStateFlow(true)
    override val textField: TextFieldViewModel = TextFieldViewModelImpl(100, "Don't forget the games 😉")
    override val isSendEnabled: StateFlow<Boolean> = MutableStateFlow(true)
    override val listOfMentions: StateFlow<List<UserInfoElement>?> = MutableStateFlow(null)
    override val listOfMentionsLoading: StateFlow<Boolean> = MutableStateFlow(false)
    override val useMarkdown: StateFlow<Boolean> = MutableStateFlow(false)
    override val showAttachmentSelectDialog: StateFlow<Boolean> = MutableStateFlow(false)

    override fun selectMention(userId: UserId) {}
    override fun closeAttachmentDialog() {}
    override fun onAttachmentFileSelect(file: FileDescriptor) {}
    override fun replaceMessage(roomId: RoomId, eventId: EventId) {}
    override fun cancelReplace() {}
    override fun replyMessage(roomId: RoomId, eventId: EventId) {}
    override fun cancelReply() {}
    override fun selectAttachment() {}
    override fun sendMessage() {}
}

class TimelineElementHolderViewModelMock(
    sender: UserInfoElement,
    element: TimelineElementViewModel<*>,
    override val formattedTime: String,
    override val isByMe: Boolean,
    showSender: Boolean,
    isFirstInUserSequence: Boolean,
    reactions: Map<String, Int> = mapOf(),
    repliedElement: TimelineElementHolderViewModel? = null,
) : TimelineElementHolderViewModel {
    override val key: String = Random.nextString(12)
    override val roomId: RoomId = selectedRoom
    override val eventId: EventId = EventId(key)
    override val showUnreadMarker: StateFlow<Boolean> = MutableStateFlow(false)
    override val canBeEdited: StateFlow<Boolean> = MutableStateFlow(false)
    override val canBeRedacted: StateFlow<Boolean> = MutableStateFlow(false)
    override val canBeRepliedTo: StateFlow<Boolean> = MutableStateFlow(false)
    override val canBeReported: StateFlow<Boolean> = MutableStateFlow(false)
    override val highlight: StateFlow<Boolean> = MutableStateFlow(false)
    override val isRead: StateFlow<Boolean> = MutableStateFlow(true)
    override val readers: StateFlow<List<UserInfoElement>?> = MutableStateFlow(listOf())
    override val isReplaced: StateFlow<Boolean> = MutableStateFlow(false)
    override val redactionError: StateFlow<String?> = MutableStateFlow(null)
    override val redactionInProgress: StateFlow<Boolean> = MutableStateFlow(false)
    override val showLoadingIndicatorAfter: StateFlow<Boolean> = MutableStateFlow(false)
    override val showLoadingIndicatorBefore: StateFlow<Boolean> = MutableStateFlow(false)
    override val reactions: StateFlow<EventReactions?> =
        MutableStateFlow(
            EventReactions(
                buildSet {
                    reactions.forEach { (reaction, number) ->
                        repeat(number) {
                            add(
                                EventReaction(
                                    reaction,
                                    UserInfoElement(UserId(number.toString(), "server"), number.toString(), ""),
                                    EventId(number.toString()),
                                    false,
                                )
                            )
                        }
                    }
                }
            )
        )
    override val canBeReactedTo: StateFlow<Boolean> = MutableStateFlow(false)
    override val element: StateFlow<TimelineElementViewModel<*>?> = MutableStateFlow(element)
    override val isReply: StateFlow<Boolean?> = MutableStateFlow(false)
    override val repliedElement: StateFlow<TimelineElementHolderViewModel?> = MutableStateFlow(repliedElement)
    override val isFirstInUserSequence: StateFlow<Boolean?> = MutableStateFlow(isFirstInUserSequence)
    override val formattedDate: String = "24.11.2025"
    override val isSent: StateFlow<Boolean> = MutableStateFlow(true)
    override val sender: StateFlow<UserInfoElement?> = MutableStateFlow(sender)
    override val showSender: StateFlow<Boolean?> = MutableStateFlow(showSender)
    override val showBigGapBefore: StateFlow<Boolean?> = MutableStateFlow(false)
    override val sendError: StateFlow<String?> = MutableStateFlow(null)

    override fun redact() {}
    override fun reply() {}
    override fun endReply() {}
    override fun report() {}
    override fun addReaction(reaction: String) {}
    override fun removeReaction(reaction: String) {}
    override fun openTimelineElementMetadata() {}
    override fun replace() {}
    override fun endReplace() {}
    override fun jumpTo() {}
}

class TextMessageViewModelMock(
    override val body: String,
) : RoomMessageTimelineElementViewModel.TextBased.Text {
    override val formattedBody: String? = null
    override val formattedBodyContent: HtmlNode.HtmlElement? =
        HtmlNode.HtmlElement("#root", emptyMap(), listOf(HtmlNode.TextContent(body)))
    override val mentionsInBody: Map<IntRange, StateFlow<TimelineElementMention?>> = mapOf()
    override val mentionsInFormattedBody: StateFlow<Map<String, TimelineElementMention?>> = MutableStateFlow(mapOf())
    override fun openMention(mention: TimelineElementMention) {}
}

class ImageMessageViewModelMock(
    thumbnail: ByteArray?,
    caption: String?,
    override val height: Int,
    override val width: Int,
) : RoomMessageTimelineElementViewModel.FileBased.Image {
    override val thumbnail: StateFlow<ByteArray?> = MutableStateFlow(thumbnail)
    override val thumbnailLoading: StateFlow<Boolean> = MutableStateFlow(false)
    override val thumbnailWidth: Int? = null
    override val thumbnailHeight: Int? = null

    @MSC2448
    override val blurhash: String? = null
    override val name: String = "image.jpg"
    override val body: String = caption ?: name
    override val formattedBody: String? = null
    override val formattedBodyContent: HtmlNode.HtmlElement? =
        HtmlNode.HtmlElement("#root", emptyMap(), listOf(HtmlNode.TextContent(body)))
    override val mentionsInBody: Map<IntRange, StateFlow<TimelineElementMention?>> = mapOf()
    override val mentionsInFormattedBody: StateFlow<Map<String, TimelineElementMention?>> = MutableStateFlow(mapOf())

    override val size: String? = null
    override val mimeType: String? = null
    override val hasCaption: Boolean = caption != null
    override val loadMediaResult: StateFlow<ByteArray?> = MutableStateFlow(null)
    override val loadMediaResultPlatformMedia: StateFlow<PlatformMedia?> = MutableStateFlow(null)
    override val loadMediaResultBytes: StateFlow<ByteArray?> = MutableStateFlow(thumbnail)
    override val loadMediaProgress: StateFlow<FileTransferProgressElement?> = MutableStateFlow(null)
    override val loadMediaError: StateFlow<String?> = MutableStateFlow(null)
    override val downloadMediaResult: StateFlow<PlatformMedia?> = MutableStateFlow(null)
    override val downloadMediaProgress: StateFlow<FileTransferProgressElement?> = MutableStateFlow(null)
    override val downloadMediaError: StateFlow<String?> = MutableStateFlow(null)

    override fun loadMedia() {}
    override fun cancelLoadMedia() {}
    override fun downloadMedia(processFile: suspend (PlatformMedia) -> Unit) {}
    override fun cancelDownloadMedia() {}
    override fun openMention(mention: TimelineElementMention) {}
}

fun resource(path: String): ByteArray? = object {}.javaClass.classLoader?.getResource(path)?.readBytes()