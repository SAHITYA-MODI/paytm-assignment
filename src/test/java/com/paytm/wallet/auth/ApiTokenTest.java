package com.paytm.wallet.auth;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The burst script proves the concurrency invariants end to end but can only observe
 * tokens as opaque strings. These cover the part it cannot: that a token is actually
 * bound to its user and cannot be forged into somebody else's.
 */
class ApiTokenTest {

    private final ApiToken apiToken = new ApiToken("test-secret");

    @Test
    void resolvesTheUserItWasMintedFor() {
        assertThat(apiToken.resolve(apiToken.mint("alice"))).contains("alice");
    }

    @Test
    void roundTripsUserIdsThatArePunctuationHeavy() {
        String userId = "user+tag@example.com/burst test";
        assertThat(apiToken.resolve(apiToken.mint(userId))).contains(userId);
    }

    @Test
    void rejectsATokenWhoseUserWasSwappedOut() {
        // The attack this exists to stop: take your own valid token, replace the encoded
        // userId with somebody else's, keep the signature.
        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("bob".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + apiToken.mint("alice").substring(apiToken.mint("alice").indexOf('.'));

        assertThat(apiToken.resolve(forged)).isEmpty();
    }

    @Test
    void rejectsATokenSignedWithADifferentSecret() {
        String fromOtherDeployment = new ApiToken("some-other-secret").mint("alice");
        assertThat(apiToken.resolve(fromOtherDeployment)).isEmpty();
    }

    @Test
    void rejectsMalformedTokensWithoutThrowing() {
        for (String malformed : new String[]{"", ".", "nodot", "a.", ".b", "!!!.???", "a.b.c"}) {
            assertThat(apiToken.resolve(malformed))
                    .as("malformed token %s", malformed)
                    .isEqualTo(Optional.empty());
        }
    }
}