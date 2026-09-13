package com.paytm.wallet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    private UUID id;

    /**
     * Opaque caller-supplied identifier, and the subject of the bearer token that
     * authenticates its owner (see com.paytm.wallet.auth.ApiToken). There is no separate
     * users table: this column, with its UNIQUE index, is both the get-or-create key for
     * POST /wallets and the identity POST /transfers authorizes against.
     */
    @Column(name = "user_id", nullable = false, unique = true)
    private String userId;

    @Column(name = "balance_paise", nullable = false)
    private long balancePaise;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Wallet() {
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public long getBalancePaise() {
        return balancePaise;
    }

    public void setBalancePaise(long balancePaise) {
        this.balancePaise = balancePaise;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
