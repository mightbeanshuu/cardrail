package dev.cardrail.sw.port;

public record AccountBalance(String token, int currency, long availableMinor, long heldMinor, AccountStatus status) {
}
