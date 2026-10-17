package dev.cardrail.sw.domain.authorization;

import java.util.random.RandomGenerator;

/** F38 authorization identification response: six characters, numeric here. */
public final class AuthCodes {

    private AuthCodes() {
    }

    public static String next(RandomGenerator random) {
        return "%06d".formatted(random.nextInt(1_000_000));
    }
}
