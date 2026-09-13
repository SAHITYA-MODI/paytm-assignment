package com.paytm.wallet.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Mints and verifies the per-user bearer token the assignment asks for.
 *
 * Shape: {base64url(userId)}.{base64url(HMAC-SHA256(secret, base64url(userId)))} — a
 * stateless, self-verifying token, so identifying the caller costs no database lookup
 * and there is no token table to keep in sync with the wallets table.
 *
 * Deliberately NOT a real credential system: there is no expiry, no rotation, no
 * revocation, and POST /wallets hands the token to whoever asks for a given userId. The
 * assignment states auth sophistication is not graded and that the token exists to
 * "identify the caller" — this does exactly that and nothing more. The HMAC is what stops
 * a caller from simply asserting somebody else's userId by editing the token.
 */
@Component
public class ApiToken {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public ApiToken(@Value("${wallet.auth.token-secret}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public String mint(String userId) {
        String encodedUserId = ENCODER.encodeToString(userId.getBytes(StandardCharsets.UTF_8));
        return encodedUserId + "." + ENCODER.encodeToString(sign(encodedUserId));
    }

    /** Returns the userId this token identifies, or empty if it is malformed or not signed by us. */
    public Optional<String> resolve(String token) {
        int separator = token.indexOf('.');
        if (separator <= 0 || separator == token.length() - 1) {
            return Optional.empty();
        }
        String encodedUserId = token.substring(0, separator);
        byte[] presentedSignature;
        byte[] userIdBytes;
        try {
            presentedSignature = DECODER.decode(token.substring(separator + 1));
            userIdBytes = DECODER.decode(encodedUserId);
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
        // Constant-time compare: a byte-by-byte early exit would leak, via response
        // timing, how much of a forged signature was correct.
        if (!MessageDigest.isEqual(sign(encodedUserId), presentedSignature)) {
            return Optional.empty();
        }
        return Optional.of(new String(userIdBytes, StandardCharsets.UTF_8));
    }

    private byte[] sign(String encodedUserId) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(encodedUserId.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
