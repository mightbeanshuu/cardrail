package dev.cardrail.clearing.integration;

import dev.cardrail.clearing.domain.settlement.PartyType;
import dev.cardrail.clearing.domain.settlement.Position;
import dev.cardrail.clearing.support.TestFixtures;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Six clearing records over two acquirers, two issuer BINs and two currencies, one of them a
 * reversal. The expected positions are worked out by hand from the illustrative rate table.
 */
final class SettlementDataset {

    static final String BIN_A = "555555******4444";
    static final String BIN_B = "411111******1111";

    static final String EXPECTED_CSV = String.join("\r\n",
            "settlement_date,party_type,party_id,currency,record_count,gross_minor,interchange_minor,net_minor",
            "%1$s,ACQUIRER,111111,356,4,320000,2220,317780",
            "%1$s,ACQUIRER,222222,356,1,45050,676,44374",
            "%1$s,ACQUIRER,222222,840,1,125000,3375,121625",
            "%1$s,ISSUER,411111,356,1,300000,2000,-298000",
            "%1$s,ISSUER,411111,840,1,125000,3375,-121625",
            "%1$s,ISSUER,555555,356,4,65050,896,-64154",
            "%1$s,NETWORK,CARDRAIL,356,5,365050,2896,0",
            "%1$s,NETWORK,CARDRAIL,840,1,125000,3375,0",
            "");

    private SettlementDataset() {
    }

    static void load(TestFixtures fixtures, LocalDate businessDate) {
        Instant at = TestFixtures.morningOf(businessDate);
        fixtures.capture("111111", BIN_A, "5411", 356, 20_000, at);      // grocery 110 bps: 220
        fixtures.capture("111111", BIN_B, "5541", 356, 300_000, at);     // fuel 50 bps + 500: 2000
        fixtures.capture("222222", BIN_A, "5812", 356, 45_050, at);      // restaurant 150 bps: 675.75 -> 676
        fixtures.capture("222222", BIN_B, "4511", 840, 125_000, at);     // travel 190 bps + 1000: 3375
        UUID reversed = fixtures.capture("111111", BIN_A, "7999", 356, 10_000, at); // default 175 bps: 175
        fixtures.reverse(reversed, at.plusSeconds(120));
    }

    static List<Position> expectedPositions() {
        return List.of(
                new Position(PartyType.ACQUIRER, "111111", 356, 4, 320_000, 2_220, 317_780),
                new Position(PartyType.ACQUIRER, "222222", 356, 1, 45_050, 676, 44_374),
                new Position(PartyType.ACQUIRER, "222222", 840, 1, 125_000, 3_375, 121_625),
                new Position(PartyType.ISSUER, "411111", 356, 1, 300_000, 2_000, -298_000),
                new Position(PartyType.ISSUER, "411111", 840, 1, 125_000, 3_375, -121_625),
                new Position(PartyType.ISSUER, "555555", 356, 4, 65_050, 896, -64_154),
                new Position(PartyType.NETWORK, "CARDRAIL", 356, 5, 365_050, 2_896, 0),
                new Position(PartyType.NETWORK, "CARDRAIL", 840, 1, 125_000, 3_375, 0));
    }

    static String expectedCsv(LocalDate businessDate) {
        return EXPECTED_CSV.formatted(businessDate);
    }
}
