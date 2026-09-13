package com.paytm.wallet.controller;

import com.paytm.wallet.auth.ApiToken;
import com.paytm.wallet.dto.CreateWalletRequest;
import com.paytm.wallet.dto.WalletResponse;
import com.paytm.wallet.entity.Wallet;
import com.paytm.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;
    private final ApiToken apiToken;

    public WalletController(WalletService walletService, ApiToken apiToken) {
        this.walletService = walletService;
        this.apiToken = apiToken;
    }

    /**
     * The one unauthenticated endpoint: it is where a caller gets the bearer token they
     * need for everything else (see AuthenticationFilter). Returns 201 whether the wallet
     * was created now or already existed — get-or-create is a single idempotent operation
     * and the caller does not need to care which happened.
     */
    @PostMapping
    public ResponseEntity<WalletResponse> createOrGet(@Valid @RequestBody CreateWalletRequest request) {
        long initialBalance = request.initialBalancePaise() == null ? 0L : request.initialBalancePaise();
        Wallet wallet = walletService.getOrCreateWallet(request.userId(), initialBalance);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WalletResponse.withToken(wallet, apiToken.mint(wallet.getUserId())));
    }

    @GetMapping("/{id}")
    public WalletResponse getById(@PathVariable UUID id) {
        return WalletResponse.from(walletService.getById(id));
    }
}