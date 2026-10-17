# cardrail-loadgen

Acquirer simulator and load generator for an ISO 8583 endpoint such as the cardrail switch.
Java 21, no runtime dependencies besides `cardrail-iso8583`.

## Run

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn -q -pl cardrail-loadgen -am package -DskipTests
java -jar cardrail-loadgen/target/cardrail-loadgen-0.1.0-SNAPSHOT.jar \
    --host 127.0.0.1 --port 8583 --connections 8 --rate 2000 --duration 60s --warmup 10s
```

`--help` lists every option. The main ones:

| Option | Default | Meaning |
|---|---|---|
| `--rate` | required | target requests per second across all connections |
| `--connections` | 4 | TCP connections; requests are spread round-robin |
| `--duration` / `--warmup` | 30s / 5s | measured period, and the period before it whose results are discarded |
| `--pans` | `generated` | `generated`, or a file with one `PAN` or `PAN,YYMM` per line (`#` comments allowed) |
| `--timeout` | 5s | per-request response timeout |
| `--csv FILE` | | append a summary row (header written when the file is new) |
| `--seed` | random | seeds the request mix; printed so a run can be repeated |

The report gives requests sent, completed (and the TPS that represents), timeouts, errors, unmatched
responses, latency p50/p90/p99/p99.9/max, the field 39 distribution, and the largest delay between
a request's due time and its actual send. Counts and latencies cover only requests due inside the
measured period.

## Open loop and coordinated omission

Requests are sent on a fixed schedule (request `i` is due at `start + i / rate`) whether or not
earlier ones have been answered, and latency is measured from that due time rather than from when
the request actually left. A closed-loop client that waits for each response before sending the
next stops sending while the server stalls, so the stall is recorded once instead of for every
request that should have been sent during it, and the tail percentiles come out far too low.
Measuring from the due time also charges any delay in the generator itself to the result, which is
why the report prints the maximum send lag: if it is large, the generator, not the server, was the
bottleneck.

## Requests

Each request is a `0100` purchase: F2 PAN, F3 `000000`, F4 amount (log-uniform 1.00-5,000.00 in minor
units), F7 transmission time in UTC, F11 STAN, F12/F13 terminal local time and date, F14 expiry, F18
a random MCC from a list of ten common ones, F19 country, F22 chip or contactless, F32 acquirer ID,
F37 RRN in the common `YDDDhh` + STAN layout, F41 a terminal ID unique per connection (`LG000000`,
`LG000001`, ...), F42 merchant, F43 name/location, F49 currency.

Generated PANs are 16-digit and Luhn-valid on the BINs of the Mastercard test card numbers that
payment processors publish (`555555`, `510510`, `222300`, `520082`), with expiries 1-48 months ahead.

## Correlation

`AcquirerClient` is one pipelined connection. It allocates STANs `000001`-`999999`, wrapping and
skipping any still outstanding, and matches each response by STAN and field 7, so a late answer to
a timed-out request is counted as unmatched instead of completing a later request that reused the
STAN.

## Latency histogram

`LatencyHistogram` is log-linear with a fixed number of buckets: each power-of-two range is split into
128 equal buckets (configurable), so a reported percentile is never below the exact value and exceeds
it by less than 1/128 of it. Recording is lock-free. Percentiles use the nearest-rank definition.

## Tests

```
mvn -q -pl cardrail-loadgen -am verify
```

The tests run against a small fake ISO 8583 server built in the test sources from the codec and
framing classes; this module does not depend on the gateway or the switch. They cover histogram
percentiles against a sorted array (jqwik), STAN wrap and correlation, timeouts and unmatched late
responses, the open-loop schedule holding through a server stall, and an end-to-end run.

Numbers from runs against that fake server say nothing about cardrail's performance and are not
published. Benchmarks of the switch, when published, come with the command that produced them.
