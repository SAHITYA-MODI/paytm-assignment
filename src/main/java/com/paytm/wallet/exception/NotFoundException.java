package com.paytm.wallet.exception;

/** Thrown when a requested resource (wallet, transfer) doesn't exist. Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
