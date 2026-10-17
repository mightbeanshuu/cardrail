package dev.cardrail.sw.adapter.web;

/** A search parameter that parsed but is out of range (size above the maximum, last4 not 4 digits...). */
class InvalidSearchException extends RuntimeException {

    InvalidSearchException(String message) {
        super(message);
    }
}
