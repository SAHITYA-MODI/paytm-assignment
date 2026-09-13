package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * The assignment specifies this body as {@code from} / {@code to} / {@code amount_paise} /
 * {@code idempotency_key}. Those are accepted as aliases alongside the descriptive
 * camelCase names, so either spelling works and a caller following the spec literally is
 * never silently parsed into a request of all-nulls.
 */
public record CreateTransferRequest(
        @JsonAlias({"from", "from_wallet_id", "source_wallet_id"}) @NotNull UUID sourceWalletId,
        @JsonAlias({"to", "to_wallet_id", "destination_wallet_id"}) @NotNull UUID destinationWalletId,
        @JsonAlias("amount_paise") @Positive long amountPaise,
        @JsonAlias("idempotency_key") @NotBlank String idempotencyKey
) {
}