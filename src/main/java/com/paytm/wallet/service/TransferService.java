package com.paytm.wallet.service;

import com.paytm.wallet.entity.Transfer;
import com.paytm.wallet.exception.ForbiddenException;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.InvalidRequestException;
import com.paytm.wallet.exception.NotFoundException;
import com.paytm.wallet.metrics.TransferMetrics;
import com.paytm.wallet.repository.TransferRepository;
import com.paytm.wallet.repository.WalletRepository;
import com.paytm.wallet.util.RequestFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final TransferExecutor transferExecutor;
    private final TransferMetrics transferMetrics;

    public TransferService(WalletRepository walletRepository, TransferRepository transferRepository,
                            TransferExecutor transferExecutor, TransferMetrics transferMetrics) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.transferExecutor = transferExecutor;
        this.transferMetrics = transferMetrics;
    }

    public Transfer transfer(String callerUserId, UUID sourceWalletId, UUID destinationWalletId,
                              long amountPaise, String idempotencyKey) {
        // A wallet transferring to itself is arithmetically a no-op (debit then credit
        // the same row), so it would quietly return SUCCEEDED having moved nothing.
        // Rejected instead: it is always a client bug, and silently succeeding on a bug
        // is worse than failing on one.
        if (sourceWalletId.equals(destinationWalletId)) {
            throw new InvalidRequestException("source and destination wallet must differ");
        }

        // Authorize before touching the idempotency table at all, so a caller who does
        // not own the source wallet can't use this endpoint to probe whether somebody
        // else's idempotency key exists.
        authorizeDebit(callerUserId, sourceWalletId);

        String fingerprint = RequestFingerprint.of(sourceWalletId, destinationWalletId, amountPaise);

        Optional<Transfer> existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        try {
            return transferExecutor.execute(sourceWalletId, destinationWalletId, amountPaise, idempotencyKey, fingerprint);
        } catch (DataIntegrityViolationException lostRace) {
            // Someone else committed a Transfer with this idempotency key in between our
            // lookup and our insert. Our own transaction (including any wallet debit/
            // credit it attempted) has already been rolled back — re-read the winner's
            // result in a fresh transaction and treat it exactly like any other replay.
            log.info("idempotency race lost, re-reading winner", kv("event", "idempotency_race_lost"), kv("idempotencyKey", idempotencyKey));
            Transfer winner = transferRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> lostRace);
            return replayOrConflict(winner, fingerprint);
        }
    }

    @Transactional(readOnly = true)
    public Transfer getById(UUID id) {
        return transferRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Transfer not found: " + id));
    }

    private void authorizeDebit(String callerUserId, UUID sourceWalletId) {
        String ownerUserId = walletRepository.findUserIdById(sourceWalletId)
                .orElseThrow(() -> new NotFoundException("Wallet not found: " + sourceWalletId));
        if (!ownerUserId.equals(callerUserId)) {
            log.warn("transfer rejected: caller does not own the source wallet",
                    kv("event", "transfer_forbidden"), kv("callerUserId", callerUserId), kv("sourceWalletId", sourceWalletId));
            throw new ForbiddenException("Authenticated user does not own source wallet " + sourceWalletId);
        }
    }

    /**
     * A same-key/different-body request is a conflict, not a replay — so the fingerprint
     * is checked BEFORE anything is logged or counted as a replay. Counting it first
     * would inflate transfers_idempotent_replays_total with requests that were actually
     * rejected, which is exactly the metric a reviewer would use to check exactly-once.
     */
    private Transfer replayOrConflict(Transfer existing, String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            log.warn("idempotency conflict: same key, different request",
                    kv("event", "idempotency_conflict"), kv("idempotencyKey", existing.getIdempotencyKey()));
            transferMetrics.recordIdempotencyConflict();
            throw new IdempotencyConflictException(
                    "idempotency key '" + existing.getIdempotencyKey() + "' was already used with a different request");
        }
        log.info("idempotent replay hit",
                kv("event", "idempotent_replay_hit"), kv("idempotencyKey", existing.getIdempotencyKey()), kv("transferId", existing.getId()));
        transferMetrics.recordIdempotentReplay();
        return existing;
    }
}