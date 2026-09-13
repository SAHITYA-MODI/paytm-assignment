package com.paytm.wallet.exception;

/** A request that is well-formed JSON but breaks a domain rule. Mapped to HTTP 400. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
