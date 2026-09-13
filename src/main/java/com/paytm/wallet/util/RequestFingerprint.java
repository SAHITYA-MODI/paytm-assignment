package com.paytm.wallet.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Hashes the fields that define "the same logical request" for idempotency checks.
 * Two requests with the same idempotency key are a safe replay only
 * if they also produce the same fingerprint; otherwise it's a conflict.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(UUID sourceWalletId, UUID destinationWalletId, long amountPaise) {
        String canonical = sourceWalletId + "|" + destinationWalletId + "|" + amountPaise;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
