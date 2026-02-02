package de.connect2x.tammy.telecryptModules.call

import de.connect2x.tammy.telecryptModules.call.callRtc.CallCoordinator
import de.connect2x.tammy.telecryptModules.call.callRtc.CallCoordinatorImpl
import de.connect2x.tammy.telecryptModules.call.callRtc.IncomingCallManager
import de.connect2x.tammy.trixnityProposal.callRtc.InMemoryMatrixRtcCallStateStore
import de.connect2x.tammy.telecryptModules.call.callRtc.MatrixRtcAutoStart
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcCallStateStore
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcService
import de.connect2x.tammy.telecryptModules.call.callRtc.MatrixRtcWatcher
import de.connect2x.tammy.telecryptModules.call.callRtc.MatrixRtcWatcherImpl
import de.connect2x.tammy.telecryptModules.call.callRtc.MatrixRtcSyncFilterConfigurer
import de.connect2x.trixnity.messenger.ConfigureMatrixClientConfiguration
import org.koin.dsl.module

fun callRtcModule() = module {
    single<CallCoordinator> { CallCoordinatorImpl(get(), get()) }
    single<MatrixRtcCallStateStore> { InMemoryMatrixRtcCallStateStore() }
    single { MatrixRtcService(get()) }
    single<MatrixRtcWatcher> { MatrixRtcWatcherImpl(get()) }
    single(createdAtStart = true) { MatrixRtcAutoStart(get(), get()) }
    single(createdAtStart = true) { IncomingCallManager(get(), get()) }
    single<ConfigureMatrixClientConfiguration> { MatrixRtcSyncFilterConfigurer() }
}
