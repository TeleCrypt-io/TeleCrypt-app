package de.connect2x.tammy.telecryptModules.call.callRtc

import net.folivo.trixnity.clientserverapi.model.users.Filters

internal val MATRIX_RTC_EVENT_TYPES = setOf(
    MatrixRtcEventTypes.SLOT,
    MatrixRtcEventTypes.MEMBER,
    MatrixRtcEventTypes.UNSTABLE_SLOT,
    MatrixRtcEventTypes.UNSTABLE_MEMBER,
)

internal fun patchFiltersForRtc(filters: Filters): Filters {
    val room = filters.room ?: return filters
    val patchedRoom = patchRoomFilterForRtc(room)
    if (patchedRoom == room) return filters
    return filters.copy(room = patchedRoom)
}

private fun patchRoomFilterForRtc(room: Filters.RoomFilter): Filters.RoomFilter {
    val state = room.state?.let { patchRoomEventFilterForRtc(it) } ?: room.state
    val timeline = room.timeline?.let { patchRoomEventFilterForRtc(it) } ?: room.timeline
    val ephemeral = room.ephemeral?.let { patchRoomEventFilterForRtc(it) } ?: room.ephemeral
    val accountData = room.accountData?.let { patchRoomEventFilterForRtc(it) } ?: room.accountData
    if (state == room.state && timeline == room.timeline && ephemeral == room.ephemeral && accountData == room.accountData) {
        return room
    }
    return room.copy(
        state = state,
        timeline = timeline,
        ephemeral = ephemeral,
        accountData = accountData,
    )
}

private fun patchRoomEventFilterForRtc(
    filter: Filters.RoomFilter.RoomEventFilter,
): Filters.RoomFilter.RoomEventFilter {
    val types = filter.types
    val notTypes = filter.notTypes
    val newTypes = if (types == null || types.isEmpty()) types else (types + MATRIX_RTC_EVENT_TYPES)
    val newNotTypes = notTypes?.minus(MATRIX_RTC_EVENT_TYPES)
    if (newTypes == types && newNotTypes == notTypes) return filter
    return filter.copy(
        types = newTypes,
        notTypes = newNotTypes,
    )
}
