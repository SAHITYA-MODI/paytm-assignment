package com.paytm.wallet.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * initialBalancePaise is optional and only takes effect the first time a given userId
 * is seen (get-or-create). It exists purely so we have a way to seed money into the
 * system for testing, since the assignment's API has no separate deposit endpoint.
 *
 * snake_case aliases are accepted for the same reason as on CreateTransferRequest.
 */
public record CreateWalletRequest(
        @JsonAlias({"user_id", "user"}) @NotBlank String userId,
        @JsonAlias({"initial_balance_paise", "balance_paise"}) @PositiveOrZero Long initialBalancePaise
) {
}