package com.paytm.wallet.repository;

import com.paytm.wallet.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {
    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);
}
