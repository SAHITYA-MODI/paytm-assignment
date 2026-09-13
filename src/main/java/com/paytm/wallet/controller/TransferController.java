package com.paytm.wallet.controller;

import com.paytm.wallet.auth.CallerContext;
import com.paytm.wallet.dto.CreateTransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.entity.Transfer;
import com.paytm.wallet.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * Always 201, including for a DECLINED transfer: a transfer resource really is
     * created and retrievable at GET /transfers/{id} either way, and the outcome is the
     * `status` field. This also makes an idempotent replay trivially return a byte-for-byte
     * identical response to the original, whatever the original outcome was. See README
     * "Response contract" — this is a deliberate call, not an accident.
     */
    @PostMapping
    public ResponseEntity<TransferResponse> create(
            @Valid @RequestBody CreateTransferRequest request,
            @RequestAttribute(CallerContext.USER_ID_ATTRIBUTE) String callerUserId) {
        Transfer transfer = transferService.transfer(
                callerUserId,
                request.sourceWalletId(),
                request.destinationWalletId(),
                request.amountPaise(),
                request.idempotencyKey()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(TransferResponse.from(transfer));
    }

    @GetMapping("/{id}")
    public TransferResponse getById(@PathVariable UUID id) {
        return TransferResponse.from(transferService.getById(id));
    }
}