package dev.cardrail.sw.port;

import java.util.OptionalLong;

public interface StandInLimits {

    /** The limit of the longest BIN prefix configured for this currency, if any. */
    OptionalLong limitFor(String bin, int currency);
}
