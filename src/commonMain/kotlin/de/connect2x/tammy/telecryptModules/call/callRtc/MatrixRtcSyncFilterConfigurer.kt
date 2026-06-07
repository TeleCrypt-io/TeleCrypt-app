package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.telecryptModules.call.callLog

import de.connect2x.trixnity.messenger.ConfigureMatrixClientConfiguration
import net.folivo.trixnity.client.MatrixClientConfiguration
import net.folivo.trixnity.clientserverapi.model.users.Filters

class MatrixRtcSyncFilterConfigurer : ConfigureMatrixClientConfiguration {
    override fun MatrixClientConfiguration.invoke() {
        syncFilter = patchFiltersForRtc(syncFilter)
        syncOnceFilter = patchFiltersForRtc(syncOnceFilter)
        if (matrixClientServerApiClientFactory !is MatrixRtcClientServerApiClientFactory) {
            matrixClientServerApiClientFactory = MatrixRtcClientServerApiClientFactory(matrixClientServerApiClientFactory)
            callLog("[Call] Wrapped MatrixClientServerApiClientFactory for RTC event mapping")
        }
        val room = syncFilter.room
        callLog(
            "[Call] Applied MatrixRTC sync filter patch." +
                " stateTypes=${room?.state?.types} stateNotTypes=${room?.state?.notTypes}" +
                " timelineTypes=${room?.timeline?.types} timelineNotTypes=${room?.timeline?.notTypes}" +
                " ephemeralTypes=${room?.ephemeral?.types} ephemeralNotTypes=${room?.ephemeral?.notTypes}" +
                " accountDataTypes=${room?.accountData?.types} accountDataNotTypes=${room?.accountData?.notTypes}"
        )
    }
}
