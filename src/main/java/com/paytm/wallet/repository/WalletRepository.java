package com.paytm.wallet.repository;

import com.paytm.wallet.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByUserId(String userId);

    /**
     * Ownership check for POST /transfers. Deliberately a scalar projection rather than
     * findById(): it answers the question without materialising a Wallet entity, so no
     * pre-lock copy of the row can ever end up in a persistence context that
     * findByIdForUpdate() might later read back instead of the freshly locked row.
     * spring.jpa.open-in-view=false already prevents that, but this does not depend on
     * it staying false.
     */
    @Query("select w.userId from Wallet w where w.id = :id")
    Optional<String> findUserIdById(@Param("id") UUID id);

    /**
     * Fetches the row with an exclusive lock (SQL `SELECT ... FOR UPDATE`).
     * Any other transaction trying to lock the same row
     * blocks until this transaction commits or rolls back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") UUID id);

    /**
     * A single atomic statement — Postgres's own unique
     * index on user_id resolves the race, not any locking we do ourselves. Returns 1 if
     * this call created the row, 0 if a concurrent call already had (or won the race to).
     */
    @Modifying
    @Query(value = """
            INSERT INTO wallets (id, user_id, balance_paise, created_at)
            VALUES (:id, :userId, :balancePaise, :createdAt)
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                        @Param("userId") String userId,
                        @Param("balancePaise") long balancePaise,
                        @Param("createdAt") Instant createdAt);
}
