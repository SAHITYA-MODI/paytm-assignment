package com.paytm.wallet.service;

import com.paytm.wallet.entity.Wallet;
import com.paytm.wallet.exception.NotFoundException;
import com.paytm.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public Wallet getOrCreateWallet(String userId, long initialBalancePaise) {
        int inserted = walletRepository.insertIfAbsent(UUID.randomUUID(), userId, initialBalancePaise,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Wallet missing immediately after insert-or-fetch for user " + userId));

        if (inserted == 1) {
            log.info("wallet created",
                    kv("event", "wallet_created"), kv("walletId", wallet.getId()),
                    kv("userId", userId), kv("initialBalancePaise", initialBalancePaise));
        } else {
            log.debug("wallet get-or-create hit existing wallet",
                    kv("event", "wallet_get_or_create_hit_existing"), kv("walletId", wallet.getId()), kv("userId", userId));
        }
        return wallet;
    }

    @Transactional(readOnly = true)
    public Wallet getById(UUID id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("wallet not found", kv("event", "wallet_not_found"), kv("walletId", id));
                    return new NotFoundException("Wallet not found: " + id);
                });
    }
}
