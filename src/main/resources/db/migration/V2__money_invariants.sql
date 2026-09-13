ALTER TABLE wallets
    ADD CONSTRAINT ck_wallets_balance_non_negative CHECK (balance_paise >= 0);

ALTER TABLE transfers
    ADD CONSTRAINT ck_transfers_amount_positive CHECK (amount_paise > 0);

CREATE INDEX idx_transfers_source_wallet_id ON transfers (source_wallet_id);
CREATE INDEX idx_transfers_destination_wallet_id ON transfers (destination_wallet_id);