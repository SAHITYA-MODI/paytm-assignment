package com.paytm.wallet.exception;

/** The caller is authenticated, but the wallet they are trying to debit isn't theirs. Mapped to HTTP 403. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}