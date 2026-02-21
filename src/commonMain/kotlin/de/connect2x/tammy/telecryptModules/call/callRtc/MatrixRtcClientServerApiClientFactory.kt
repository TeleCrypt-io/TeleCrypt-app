package de.connect2x.tammy.telecryptModules.call.callRtc

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.Url
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.Json
import net.folivo.trixnity.clientserverapi.client.MatrixAuthProvider
import net.folivo.trixnity.clientserverapi.client.MatrixClientServerApiClient
import net.folivo.trixnity.clientserverapi.client.MatrixClientServerApiClientFactory
import net.folivo.trixnity.clientserverapi.client.SyncBatchTokenStore
import net.folivo.trixnity.core.model.events.UnknownEventContent
import net.folivo.trixnity.core.serialization.events.EventContentSerializerMappings
import net.folivo.trixnity.core.serialization.events.UnknownEventContentSerializer
import net.folivo.trixnity.core.serialization.events.createEventContentSerializerMappings
import net.folivo.trixnity.core.serialization.events.ephemeralOf
import net.folivo.trixnity.core.serialization.events.messageOf
import net.folivo.trixnity.core.serialization.events.roomAccountDataOf
import net.folivo.trixnity.core.serialization.events.stateOf
import net.folivo.trixnity.utils.RetryFlowDelayConfig

class MatrixRtcClientServerApiClientFactory(
    private val delegate: MatrixClientServerApiClientFactory,
) : MatrixClientServerApiClientFactory {
    override fun create(
        baseUrl: Url?,
        authProvider: MatrixAuthProvider,
        eventContentSerializerMappings: EventContentSerializerMappings,
        json: Json,
        syncBatchTokenStore: SyncBatchTokenStore,
        syncErrorDelayConfig: RetryFlowDelayConfig,
        coroutineContext: CoroutineContext,
        httpClientEngine: HttpClientEngine?,
        httpClientConfig: (HttpClientConfig<*>.() -> Unit)?,
    ): MatrixClientServerApiClient {
        val rtcMappings = buildRtcMappings()
        val combined = eventContentSerializerMappings.plus(rtcMappings)
        println("[Call] MatrixClientServerApiClientFactory.create added RTC mappings")
        return delegate.create(
            baseUrl = baseUrl,
            authProvider = authProvider,
            eventContentSerializerMappings = combined,
            json = json,
            syncBatchTokenStore = syncBatchTokenStore,
            syncErrorDelayConfig = syncErrorDelayConfig,
            coroutineContext = coroutineContext,
            httpClientEngine = httpClientEngine,
            httpClientConfig = httpClientConfig,
        )
    }
}

private fun buildRtcMappings(): EventContentSerializerMappings =
    createEventContentSerializerMappings {
        val slotTypes = listOf(
            MatrixRtcEventTypes.SLOT,
            MatrixRtcEventTypes.UNSTABLE_SLOT,
        )
        for (type in slotTypes) {
            stateOf<UnknownEventContent>(type, UnknownEventContentSerializer(type))
        }

        val memberTypes = listOf(
            MatrixRtcEventTypes.MEMBER,
            MatrixRtcEventTypes.UNSTABLE_MEMBER,
        )
        for (type in memberTypes) {
            stateOf<UnknownEventContent>(type, UnknownEventContentSerializer(type))
            messageOf<UnknownEventContent>(type, UnknownEventContentSerializer(type))
            ephemeralOf<UnknownEventContent>(type, UnknownEventContentSerializer(type))
            roomAccountDataOf<UnknownEventContent>(type, UnknownEventContentSerializer(type))
        }
    }
