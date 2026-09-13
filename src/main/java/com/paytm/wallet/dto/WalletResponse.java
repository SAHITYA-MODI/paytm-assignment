package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paytm.wallet.entity.Wallet;

import java.time.Instant;
import java.util.UUID;

/**
 * apiToken is populated only by POST /wallets, which is how a caller obtains the bearer
 * token for their user. GET /wallets/{id} omits it (JSON null fields are suppressed), so
 * reading a wallet never hands out the credential for it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WalletResponse(
        UUID id,
        String userId,
        long balancePaise,
        Instant createdAt,
        String apiToken
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.getId(), wallet.getUserId(), wallet.getBalancePaise(), wallet.getCreatedAt(), null);
    }

    public static WalletResponse withToken(Wallet wallet, String apiToken) {
        return new WalletResponse(wallet.getId(), wallet.getUserId(), wallet.getBalancePaise(), wallet.getCreatedAt(), apiToken);
    }
}