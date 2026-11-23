package com.jack.mc.common.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewaySignerTest {

    private static final String SECRET = "01234567890123456789012345678901";

    @Test
    void signatureCoversUserRoleAndTimestamp() {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String sign = GatewaySigner.sign(SECRET, "1001", "USER", timestamp);

        assertTrue(GatewaySigner.verify(SECRET, "1001", "USER", timestamp, sign, 300));
        assertFalse(GatewaySigner.verify(SECRET, "1001", "ADMIN", timestamp, sign, 300));
        assertFalse(GatewaySigner.verify(SECRET, "1002", "USER", timestamp, sign, 300));
    }

    @Test
    void signatureRejectsExpiredTimestamp() {
        String timestamp = "1";
        String sign = GatewaySigner.sign(SECRET, "1001", "USER", timestamp);

        assertFalse(GatewaySigner.verify(SECRET, "1001", "USER", timestamp, sign, 300));
    }
}
