package com.paytm.wallet.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fingerprint is what separates "safe replay" from "409 conflict", so a collision
 * here would silently turn a conflicting request into a replay — i.e. return someone a
 * result for a transfer they did not ask for.
 */
class RequestFingerprintTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    void isStableForTheSameRequest() {
        assertThat(RequestFingerprint.of(A, B, 1500)).isEqualTo(RequestFingerprint.of(A, B, 1500));
    }

    @Test
    void differsWhenTheAmountDiffers() {
        assertThat(RequestFingerprint.of(A, B, 1500)).isNotEqualTo(RequestFingerprint.of(A, B, 1501));
    }

    @Test
    void differsWhenTheDirectionIsReversed() {
        assertThat(RequestFingerprint.of(A, B, 1500)).isNotEqualTo(RequestFingerprint.of(B, A, 1500));
    }

    @Test
    void differsWhenSourceAndDestinationAreTheSameWallet() {
        assertThat(RequestFingerprint.of(A, A, 1500)).isNotEqualTo(RequestFingerprint.of(A, B, 1500));
    }
}