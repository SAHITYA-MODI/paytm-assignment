-- Initial schema (learning.md #17). Matches Wallet/Transfer entities exactly; Hibernate
-- runs in ddl-auto=validate mode and checks this against the entities at startup.

CREATE TABLE wallets (
    id            UUID PRIMARY KEY,
    user_id       VARCHAR(255) NOT NULL,
    balance_paise BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_wallets_user_id UNIQUE (user_id)
);

CREATE TABLE transfers (
    id                    UUID PRIMARY KEY,
    source_wallet_id      UUID         NOT NULL,
    destination_wallet_id UUID         NOT NULL,
    amount_paise          BIGINT       NOT NULL,
    status                VARCHAR(255) NOT NULL CHECK (status IN ('SUCCEEDED', 'DECLINED')),
    decline_reason        VARCHAR(255),
    idempotency_key       VARCHAR(255) NOT NULL,
    request_fingerprint   VARCHAR(255) NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_transfers_idempotency_key UNIQUE (idempotency_key)
);