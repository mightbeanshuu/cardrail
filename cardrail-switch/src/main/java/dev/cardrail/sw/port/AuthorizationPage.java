package dev.cardrail.sw.port;

import dev.cardrail.sw.domain.authorization.StoredAuthorization;

import java.util.List;

/** @param total all matches, not just this page */
public record AuthorizationPage(List<StoredAuthorization> items, long total) {

    public AuthorizationPage {
        items = List.copyOf(items);
    }
}
