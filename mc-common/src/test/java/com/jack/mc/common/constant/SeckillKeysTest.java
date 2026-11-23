package com.jack.mc.common.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SeckillKeysTest {

    @Test
    void pendingMemberContainsReservationAndRoundTrips() {
        String member = SeckillKeys.pendingMember(1001L, "reservation-1");
        SeckillKeys.PendingReservation parsed = SeckillKeys.parsePendingMember(member);

        assertEquals("1001:reservation-1", member);
        assertEquals(1001L, parsed.userId());
        assertEquals("reservation-1", parsed.reservationId());
    }

    @Test
    void legacyPendingMemberIsRecognised() {
        SeckillKeys.PendingReservation parsed = SeckillKeys.parsePendingMember("1001");

        assertEquals(1001L, parsed.userId());
        assertEquals("legacy", parsed.reservationId());
        assertNull(SeckillKeys.parsePendingMember("bad-member"));
    }
}
