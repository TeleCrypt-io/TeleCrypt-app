package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProvider
import de.connect2x.trixnity.clientserverapi.client.MatrixClientServerApiClient
import de.connect2x.trixnity.clientserverapi.client.MatrixClientServerApiClientFactory
import de.connect2x.trixnity.clientserverapi.client.SyncBatchTokenStore
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.UnknownEventContent
import de.connect2x.trixnity.core.serialization.events.EventContentSerializerMappings
import de.connect2x.trixnity.core.serialization.events.UnknownEventContentSerializer
import de.connect2x.trixnity.core.serialization.events.ephemeralOf
import de.connect2x.trixnity.core.serialization.events.invoke
import de.connect2x.trixnity.core.serialization.events.messageOf
import de.connect2x.trixnity.core.serialization.events.roomAccountDataOf
import de.connect2x.trixnity.core.serialization.events.stateOf
import de.connect2x.trixnity.utils.RetryFlowDelayConfig
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.Url
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.Json

class MatrixRtcClientServerApiClientFactory(
    private val delegate: MatrixClientServerApiClientFactory,
) : MatrixClientServerApiClientFactory {
    override fun create(
        authProvider: MatrixClientAuthProvider,
        eventContentSerializerMappings: EventContentSerializerMappings,
        json: Json,
        syncBatchTokenStore: SyncBatchTokenStore,
        syncErrorDelayConfig: RetryFlowDelayConfig,
        coroutineContext: CoroutineContext,
        asUserId: UserId?,
        asDeviceId: String?,
        httpClientEngine: HttpClientEngine?,
        httpClientConfig: (HttpClientConfig<*>.() -> Unit)?
    ): MatrixClientServerApiClient {
        val rtcMappings = buildRtcMappings()
        val combined = eventContentSerializerMappings.plus(rtcMappings)
        println("[Call] MatrixClientServerApiClientFactory.create added RTC mappings")
        return delegate.create(
            authProvider = authProvider,
            eventContentSerializerMappings = combined,
            json = json,
            syncBatchTokenStore = syncBatchTokenStore,
            syncErrorDelayConfig = syncErrorDelayConfig,
            coroutineContext = coroutineContext,
            httpClientEngine = httpClientEngine,
            httpClientConfig = httpClientConfig,
            asUserId = asUserId,
            asDeviceId = asDeviceId,
        )
    }

    override fun create(
        baseUrl: Url,
        eventContentSerializerMappings: EventContentSerializerMappings,
        json: Json,
        syncBatchTokenStore: SyncBatchTokenStore,
        syncErrorDelayConfig: RetryFlowDelayConfig,
        coroutineContext: CoroutineContext,
        asUserId: UserId?,
        asDeviceId: String?,
        httpClientEngine: HttpClientEngine?,
        httpClientConfig: (HttpClientConfig<*>.() -> Unit)?
    ): MatrixClientServerApiClient {
        val rtcMappings = buildRtcMappings()
        val combined = eventContentSerializerMappings.plus(rtcMappings)
        println("[Call] MatrixClientServerApiClientFactory.create added RTC mappings")
        return delegate.create(
            baseUrl = baseUrl,
            eventContentSerializerMappings = combined,
            json = json,
            syncBatchTokenStore = syncBatchTokenStore,
            syncErrorDelayConfig = syncErrorDelayConfig,
            coroutineContext = coroutineContext,
            asUserId = asUserId,
            asDeviceId = asDeviceId,
            httpClientEngine = httpClientEngine,
            httpClientConfig = httpClientConfig
        )
    }
}

private fun buildRtcMappings(): EventContentSerializerMappings =
    EventContentSerializerMappings {
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
