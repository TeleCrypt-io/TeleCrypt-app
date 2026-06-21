package de.connect2x.tammy.telecryptModules.call.callRtc

import net.folivo.trixnity.clientserverapi.model.users.Filters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MatrixRtcFiltersTest {

    @Test
    fun nullRoomFilterIsReturnedUnchanged() {
        val filters = Filters(room = null)
        assertSame(filters, patchFiltersForRtc(filters))
    }

    @Test
    fun rtcTypesAreAddedToANonEmptyAllowList() {
        val filters = Filters(
            room = Filters.RoomFilter(
                state = Filters.RoomFilter.RoomEventFilter(types = setOf("m.room.member")),
            ),
        )
        val patched = patchFiltersForRtc(filters)
        val types = patched.room?.state?.types.orEmpty()
        assertTrue(types.contains("m.room.member"))
        assertTrue(types.containsAll(MATRIX_RTC_EVENT_TYPES), "expected RTC types in $types")
    }

    @Test
    fun rtcTypesAreRemovedFromANotTypesBlockList() {
        val filters = Filters(
            room = Filters.RoomFilter(
                timeline = Filters.RoomFilter.RoomEventFilter(
                    notTypes = setOf(MatrixRtcEventTypes.MSC3401_CALL_MEMBER, "m.room.message"),
                ),
            ),
        )
        val patched = patchFiltersForRtc(filters)
        val notTypes = patched.room?.timeline?.notTypes.orEmpty()
        assertTrue("m.room.message" in notTypes)
        assertTrue(MatrixRtcEventTypes.MSC3401_CALL_MEMBER !in notTypes)
    }

    @Test
    fun emptyAllowListIsLeftUntouched() {
        // An empty types set means "no events"; we must not turn it into an allow-list.
        val filters = Filters(
            room = Filters.RoomFilter(
                state = Filters.RoomFilter.RoomEventFilter(types = emptySet()),
            ),
        )
        val patched = patchFiltersForRtc(filters)
        assertEquals(emptySet(), patched.room?.state?.types)
    }
}
