package com.jack.mc.common.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtUtilsTest {

    private static final String SECRET = "01234567890123456789012345678901";

    @Test
    void issuedRoleIsVerifiedAndParsed() {
        JwtUtils jwt = new JwtUtils(SECRET, 60);

        Claims claims = jwt.parse(jwt.issue(1001L, "tester", "ADMIN"));

        assertNotNull(claims);
        assertEquals(1001L, claims.get(JwtUtils.CLAIM_USER_ID, Long.class));
        assertEquals("ADMIN", claims.get(JwtUtils.CLAIM_ROLE, String.class));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtUtils issuer = new JwtUtils(SECRET, 60);
        JwtUtils verifier = new JwtUtils("abcdefghijklmnopqrstuvwxyz123456", 60);

        assertNull(verifier.parse(issuer.issue(1001L, "tester", "USER")));
    }
}
