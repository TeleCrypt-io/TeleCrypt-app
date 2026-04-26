package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcService
import de.connect2x.trixnity.messenger.MatrixClients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.store.AccountStore
import net.folivo.trixnity.core.model.UserId

class MatrixRtcAutoStart(
    private val matrixClients: MatrixClients,
    private val rtcService: MatrixRtcService,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handlers = mutableMapOf<String, HandlerEntry>()
    private val upgradedFilterUsers = mutableSetOf<String>()

    init {
        println("[Call] MatrixRtcAutoStart initialized")
        scope.launch {
            matrixClients.collectLatest { clients ->
                ensureHandlers(clients)
            }
        }
    }

    private suspend fun ensureHandlers(clients: Map<UserId, MatrixClient>) {
        for ((userId, client) in clients) {
            val key = userId.full
            val existing = handlers[key]
            if (existing != null && existing.client === client) {
                continue
            }
            val handler = createHandler(client, key) ?: continue
            handlers[key] = HandlerEntry(client, handler)
            handler.startInCoroutineScope(scope)
            println("[Call] Auto-start RTC handler user=$key")
        }
    }

    private suspend fun createHandler(client: MatrixClient, userKey: String): MatrixRtcSyncEventHandler? {
        val accountStore = runCatching { client.di.get<AccountStore>() }.getOrNull()
        if (accountStore == null) {
            println("[Call] Auto-start RTC handler skipped (no AccountStore)")
            return null
        }
        ensureRtcFilters(client, userKey, accountStore)
        return MatrixRtcSyncEventHandler(client.api.sync, rtcService, accountStore)
    }

    private suspend fun ensureRtcFilters(
        client: MatrixClient,
        userKey: String,
        accountStore: AccountStore,
    ) {
        if (!upgradedFilterUsers.add(userKey)) return
        val account = accountStore.getAccount() ?: return
        val userId = account.userId
        val userApi = client.api.user
        var newFilterId = account.filterId
        var newBackgroundFilterId = account.backgroundFilterId
        var changed = false

        // DIAGNOSTIC: Log filter IDs to detect if filter patching is ever attempted
        println("[Call][DIAG] ensureRtcFilters user=$userKey filterId=${account.filterId} backgroundFilterId=${account.backgroundFilterId}")

        if (account.filterId != null) {
            val current = runCatching { userApi.getFilter(userId, account.filterId!!) }.getOrNull()?.getOrNull()
            println("[Call][DIAG] Current sync filter for user=$userKey: ${current?.room?.state?.types} notTypes=${current?.room?.state?.notTypes}")
            if (current != null) {
                val patched = patchFiltersForRtc(current)
                val filterChanged = patched != current
                println("[Call][DIAG] Filter patch needed=$filterChanged for user=$userKey")
                if (filterChanged) {
                    val created = runCatching { userApi.setFilter(userId, patched) }.getOrNull()?.getOrNull()
                    println("[Call][DIAG] New filter upload result=$created for user=$userKey")
                    if (!created.isNullOrBlank()) {
                        newFilterId = created
                        changed = true
                        println("[Call] Updated sync filter for user=$userKey id=$created")
                    }
                }
            } else {
                println("[Call][DIAG] Could not fetch current filter for user=$userKey filterId=${account.filterId}")
            }
        } else {
            // No filterId set yet — the server uses no filter, so all events (including RTC)
            // pass through. When trixnity-messenger eventually creates a filter,
            // MatrixRtcSyncFilterConfigurer ensures RTC types are included.
            println("[Call][DIAG] No filterId set for user=$userKey — no filter to patch (all events pass through)")
        }

        if (account.backgroundFilterId != null) {
            val current = runCatching { userApi.getFilter(userId, account.backgroundFilterId!!) }.getOrNull()?.getOrNull()
            println("[Call][DIAG] Current background filter for user=$userKey: ${current?.room?.state?.types} notTypes=${current?.room?.state?.notTypes}")
            if (current != null) {
                val patched = patchFiltersForRtc(current)
                val bgFilterChanged = patched != current
                println("[Call][DIAG] Background filter patch needed=$bgFilterChanged for user=$userKey")
                if (bgFilterChanged) {
                    val created = runCatching { userApi.setFilter(userId, patched) }.getOrNull()?.getOrNull()
                    println("[Call][DIAG] New background filter upload result=$created for user=$userKey")
                    if (!created.isNullOrBlank()) {
                        newBackgroundFilterId = created
                        changed = true
                        println("[Call] Updated background sync filter for user=$userKey id=$created")
                    }
                }
            }
        }

        if (!changed) {
            println("[Call][DIAG] No filter changes applied for user=$userKey")
            return
        }
        accountStore.updateAccount { current ->
            current!!.copy(
                filterId = newFilterId,
                backgroundFilterId = newBackgroundFilterId,
            )
        }
        runCatching {
            client.api.sync.stop()
            client.api.sync.start()
        }.onSuccess {
            println("[Call] Restarted sync to apply RTC filters for user=$userKey")
        }.onFailure { error ->
            println("[Call] Failed to restart sync for user=$userKey: ${error.message}")
        }
    }

    override fun close() {
        scope.cancel()
    }

    private data class HandlerEntry(
        val client: MatrixClient,
        val handler: MatrixRtcSyncEventHandler,
    )
}
