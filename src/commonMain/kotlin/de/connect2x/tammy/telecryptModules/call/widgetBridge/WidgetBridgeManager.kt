package de.connect2x.tammy.telecryptModules.call.widgetBridge

import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.core.model.RoomId

/**
 * Cross‑platform abstraction поверх локального WebSocket‑моста для widget‑режима EC.
 *
 * - На **desktop** реализуется через [WidgetBridgeServer] (поднимает HTTP+WS на 127.0.0.1).
 * - На **android / web** возвращает `null` — там widget‑режим пока не поддержан, и
 *   звонок открывается по старой логике (standalone EC), как и до этих изменений.
 *
 * Жизненный цикл: создаётся в начале звонка через [start], закрывается в конце через
 * [BridgeSession.close]. На один звонок — один [BridgeSession].
 */
interface WidgetBridgeManager {
    /**
     * Поднимает мост и возвращает URL host‑страницы (то, что нужно открыть в Chrome
     * вместо прямого `https://call.element.io/...`). Если платформа не поддерживает
     * widget‑режим, возвращает `null` — caller должен fallback'нуться на standalone URL.
     *
     * @param matrixClient клиент для отправки send_to_device / sendStateEvent
     * @param roomId       комната звонка
     * @param userId       MXID пользователя (без `@`-префикса, как EC ожидает)
     * @param deviceId     device_id текущей сессии
     * @param baseUrl      homeserver URL (https://...)
     * @param standaloneEcUrl стандартный EC URL — нужен, если bridge поднимется,
     *                        но мы захотим вернуть не widget‑режим
     * @param widgetEcUrlBuilder лямбда, формирующая widget‑URL внутри iframe;
     *                          вызывается с (parentUrl, widgetId) после получения порта
     */
    suspend fun start(
        matrixClient: MatrixClient,
        roomId: RoomId,
        userId: String,
        deviceId: String,
        baseUrl: String,
        widgetEcUrlBuilder: (parentUrl: String, widgetId: String) -> String,
    ): BridgeSession?

    interface BridgeSession {
        val hostUrl: String
        fun close()
    }
}

/** Заглушка для платформ, где widget‑bridge ещё не реализован. */
class NoopWidgetBridgeManager : WidgetBridgeManager {
    override suspend fun start(
        matrixClient: MatrixClient,
        roomId: RoomId,
        userId: String,
        deviceId: String,
        baseUrl: String,
        widgetEcUrlBuilder: (parentUrl: String, widgetId: String) -> String,
    ): WidgetBridgeManager.BridgeSession? = null
}
