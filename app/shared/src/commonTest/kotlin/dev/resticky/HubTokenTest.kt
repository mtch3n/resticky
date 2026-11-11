package dev.resticky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubTokenTest {
    private val token = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

    @Test
    fun proofIsHmacSha256OfTheNonceText() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            helloProof("4a656665", "what do ya want for nothing?"),
        )
    }

    @Test
    fun proofDependsOnTokenAndNonce() {
        val nonce = "0123456789abcdef0123456789abcdef"
        val proof = helloProof(token, nonce)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(proof))
        assertEquals(proof, helloProof(token, nonce))
        assertTrue(proof != helloProof(token, nonce.reversed()))
        assertTrue(proof != helloProof(token.reversed(), nonce))
    }

    @Test
    fun bearerMustMatchExactly() {
        assertTrue(bearerOk("Bearer $token", token))
        assertFalse(bearerOk(null, token))
        assertFalse(bearerOk("", token))
        assertFalse(bearerOk(token, token))
        assertFalse(bearerOk("Bearer ${token.dropLast(1)}", token))
        assertFalse(bearerOk("Bearer ${token}0", token))
        assertFalse(bearerOk("Bearer ${token.uppercase()}", token))
        assertFalse(bearerOk("bearer $token", token))
    }

    @Test
    fun tokenPatternIsLowerHex64() {
        assertTrue(TOKEN_PATTERN.matches(token))
        assertTrue(TOKEN_PATTERN.matches(ByteArray(32) { it.toByte() }.hex()))
        assertFalse(TOKEN_PATTERN.matches(token.dropLast(2)))
        assertFalse(TOKEN_PATTERN.matches(token.uppercase()))
    }
}
