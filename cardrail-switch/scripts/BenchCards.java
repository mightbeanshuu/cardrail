import dev.cardrail.loadgen.CardPool;

import java.time.YearMonth;

/**
 * Prints the request body for POST /dev/accounts: the PANs of
 * {@code CardPool.generated(cards, seed, now)}, which is exactly the pool the load generator draws
 * from when run with the same {@code --cards} and {@code --seed}.
 *
 * <p>Run as a single-file program with the load generator jar on the class path:
 * {@code java -cp cardrail-loadgen.jar BenchCards.java CARDS SEED BALANCE_MINOR CURRENCY}
 */
public class BenchCards {

    public static void main(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: BenchCards CARDS SEED BALANCE_MINOR CURRENCY");
        }
        int cards = Integer.parseInt(args[0]);
        long seed = Long.parseLong(args[1]);
        long balanceMinor = Long.parseLong(args[2]);
        int currency = Integer.parseInt(args[3]);

        CardPool pool = CardPool.generated(cards, seed, YearMonth.now());
        StringBuilder body = new StringBuilder("{\"balanceMinor\":").append(balanceMinor)
                .append(",\"currency\":").append(currency).append(",\"pans\":[");
        for (int i = 0; i < pool.size(); i++) {
            body.append(i == 0 ? "" : ",").append('"').append(pool.get(i).pan()).append('"');
        }
        System.out.println(body.append("]}"));
    }
}
