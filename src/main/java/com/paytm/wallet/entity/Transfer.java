package com.paytm.wallet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    private UUID id;

    @Column(name = "source_wallet_id", nullable = false)
    private UUID sourceWalletId;

    @Column(name = "destination_wallet_id", nullable = false)
    private UUID destinationWalletId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(name = "decline_reason")
    private String declineReason;

    /**
     * Required, and unique at the DB level. This is the exactly-once dedup point
     * (learning.md #11.3, Option C1): a repeat POST /transfers with the same key is
     * detected by looking this column up before doing anything else.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    /**
     * Hash of (sourceWalletId, destinationWalletId, amountPaise) from the original
     * request. Lets us tell a genuine replay (same key, same fingerprint) apart from a
     * conflicting reuse (same key, different fingerprint -> 409). See
     * com.paytm.wallet.util.RequestFingerprint.
     */
    @Column(name = "request_fingerprint", nullable = false)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Transfer() {
        // required by JPA
    }

    public Transfer(UUID sourceWalletId, UUID destinationWalletId, long amountPaise,
                     String idempotencyKey, String requestFingerprint) {
        this.id = UUID.randomUUID();
        this.sourceWalletId = sourceWalletId;
        this.destinationWalletId = destinationWalletId;
        this.amountPaise = amountPaise;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public void markSucceeded() {
        this.status = TransferStatus.SUCCEEDED;
    }

    public void markDeclined(String reason) {
        this.status = TransferStatus.DECLINED;
        this.declineReason = reason;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceWalletId() {
        return sourceWalletId;
    }

    public UUID getDestinationWalletId() {
        return destinationWalletId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getDeclineReason() {
        return declineReason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
