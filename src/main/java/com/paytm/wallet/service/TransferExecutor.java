package com.paytm.wallet.service;

import com.paytm.wallet.entity.Transfer;
import com.paytm.wallet.entity.Wallet;
import com.paytm.wallet.exception.NotFoundException;
import com.paytm.wallet.metrics.TransferMetrics;
import com.paytm.wallet.repository.TransferRepository;
import com.paytm.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;


@Service
public class TransferExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransferExecutor.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final TransferMetrics transferMetrics;

    public TransferExecutor(WalletRepository walletRepository, TransferRepository transferRepository,
                             TransferMetrics transferMetrics) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.transferMetrics = transferMetrics;
    }

    @Transactional
    public Transfer execute(UUID sourceWalletId, UUID destinationWalletId, long amountPaise,
                             String idempotencyKey, String requestFingerprint) {
        UUID firstId = sourceWalletId.compareTo(destinationWalletId) <= 0 ? sourceWalletId : destinationWalletId;
        UUID secondId = firstId.equals(sourceWalletId) ? destinationWalletId : sourceWalletId;

        Wallet first = requireWalletForUpdate(firstId);
        Wallet second = secondId.equals(firstId) ? first : requireWalletForUpdate(secondId);

        Wallet source = sourceWalletId.equals(firstId) ? first : second;
        Wallet destination = destinationWalletId.equals(firstId) ? first : second;

        Transfer transfer = new Transfer(sourceWalletId, destinationWalletId, amountPaise, idempotencyKey, requestFingerprint);

        if (source.getBalancePaise() < amountPaise) {
            transfer.markDeclined("INSUFFICIENT_FUNDS");
            Transfer declined = transferRepository.saveAndFlush(transfer);
            log.info("transfer declined",
                    kv("event", "transfer_declined"), kv("transferId", declined.getId()), kv("reason", "INSUFFICIENT_FUNDS"),
                    kv("sourceWalletId", sourceWalletId), kv("amountPaise", amountPaise), kv("availableBalancePaise", source.getBalancePaise()));
            transferMetrics.recordDeclinedInsufficientFunds();
            return declined;
        }

        source.setBalancePaise(source.getBalancePaise() - amountPaise);
        walletRepository.save(source);
        destination.setBalancePaise(destination.getBalancePaise() + amountPaise);
        walletRepository.save(destination);
        transfer.markSucceeded();

        Transfer saved = transferRepository.saveAndFlush(transfer);

        log.info("wallet debited",
                kv("event", "wallet_debited"), kv("walletId", source.getId()), kv("amountPaise", amountPaise), kv("newBalancePaise", source.getBalancePaise()));
        log.info("wallet credited",
                kv("event", "wallet_credited"), kv("walletId", destination.getId()), kv("amountPaise", amountPaise), kv("newBalancePaise", destination.getBalancePaise()));
        log.info("transfer succeeded",
                kv("event", "transfer_succeeded"), kv("transferId", saved.getId()),
                kv("sourceWalletId", sourceWalletId), kv("destinationWalletId", destinationWalletId), kv("amountPaise", amountPaise));
        transferMetrics.recordSucceeded();
        return saved;
    }

    private Wallet requireWalletForUpdate(UUID id) {
        return walletRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Wallet not found: " + id));
    }
}
