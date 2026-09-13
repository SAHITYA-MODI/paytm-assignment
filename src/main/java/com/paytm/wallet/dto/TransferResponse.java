package com.paytm.wallet.dto;

import com.paytm.wallet.entity.Transfer;
import com.paytm.wallet.entity.TransferStatus;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long amountPaise,
        TransferStatus status,
        String declineReason,
        Instant createdAt
) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getSourceWalletId(),
                transfer.getDestinationWalletId(),
                transfer.getAmountPaise(),
                transfer.getStatus(),
                transfer.getDeclineReason(),
                transfer.getCreatedAt()
        );
    }
}
