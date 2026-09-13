package com.paytm.wallet.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class TransferMetrics {

    private static final String TRANSFERS = "transfers_total";

    private final Counter succeeded;
    private final Counter declinedInsufficientFunds;
    private final Counter idempotentReplays;
    private final Counter idempotencyConflicts;

    public TransferMetrics(MeterRegistry registry) {
        this.succeeded = Counter.builder(TRANSFERS)
                .description("Transfers created, by outcome")
                .tag("outcome", "succeeded")
                .register(registry);
        this.declinedInsufficientFunds = Counter.builder(TRANSFERS)
                .description("Transfers created, by outcome")
                .tag("outcome", "declined_insufficient_funds")
                .register(registry);
        this.idempotentReplays = Counter.builder("transfers_idempotent_replays_total")
                .description("Requests that returned an existing transfer instead of moving money again")
                .register(registry);
        this.idempotencyConflicts = Counter.builder("transfers_idempotency_conflicts_total")
                .description("Requests rejected with 409: idempotency key reused with a different body")
                .register(registry);
    }

    public void recordSucceeded() {
        succeeded.increment();
    }

    public void recordDeclinedInsufficientFunds() {
        declinedInsufficientFunds.increment();
    }

    public void recordIdempotentReplay() {
        idempotentReplays.increment();
    }

    public void recordIdempotencyConflict() {
        idempotencyConflicts.increment();
    }
}