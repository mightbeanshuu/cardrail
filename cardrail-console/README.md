# cardrail-console

Operations console for cardrail. A support engineer uses it to search authorizations, reverse or
capture them, verify the audit hash chain, run end-of-day settlement and (in dev) inject issuer
faults to show stand-in processing. It is UI only and keeps no state of its own; everything comes
from `cardrail-switch` (8080) and `cardrail-clearing` (8081). The API contract is
[`docs/CONTRACTS.md`](../docs/CONTRACTS.md), sections 2 and 3.

Angular 22, standalone components, signals, functional interceptors, lazy routes. No UI component
library and no external fonts or CDNs.

## Pages

| Route | What it does |
|---|---|
| `/authorizations` | Filter (status, response code, merchant, last 4, stand-in, UTC date range), server-side paging, newest first. Filters live in the query string, so a URL can be shared and Back works. |
| `/authorizations/:id` | All fields, a status timeline, Reverse (with confirmation) and full or partial Capture. Both are enabled only for `AUTHORIZED`. |
| `/audit` | Runs `GET /v1/audit/verify` and shows the record count or the first broken seq. |
| `/settlement` | Pick a business date, see clearing records, run settlement, see net positions per acquirer / issuer BIN / network with a per-currency reconciliation total that must be zero, download the CSV. |
| `/dev/issuer` | Shown only when `devTools` is on. Reads and sets the simulated issuer's latency and failure rate (`GET`/`PUT`/`DELETE /dev/issuer/faults`), so a demo can push the switch into STIP. Requires the switch's `dev` profile. |

## Running against the local services

Requires Node `^22.22.3`, `^24.15.0` or `>=26` (an Angular CLI 22 requirement).

```sh
npm install
npm start            # ng serve on http://localhost:4200 with proxy.conf.json
```

`proxy.conf.json` forwards:

| Browser path | Goes to |
|---|---|
| `/v1/authorizations`, `/v1/audit`, `/dev/issuer/faults` | `http://localhost:8080` (switch) |
| `/clearing/*` | `http://localhost:8081/v1/*` (clearing) |

Base URLs and the `devTools` flag live in `src/environments/environment*.ts`
(`environment.development.ts` is used by `ng serve` and turns `devTools` on; the production build
turns it off). To call the services directly instead of through the proxy, set `switchBaseUrl` to
`http://localhost:8080` and `clearingBaseUrl` to `http://localhost:8081/v1`; both services allow
`http://localhost:4200` via CORS.

Every request carries an `X-Correlation-Id`. Failed requests are turned from RFC 7807
`application/problem+json` into a typed `ApiError` and shown in a dismissible banner with the HTTP
status and the correlation id, so the matching log lines can be found in the service.

## Tests and build

```sh
npm run test:ci      # ng test --watch=false (Vitest + jsdom, headless, no browser needed)
npm test             # watch mode
npx ng build         # production build into dist/
```

HTTP is mocked with `HttpTestingController` throughout, so no service needs to be running.

## Money handling

Amounts are integers in minor units everywhere (paise for INR 356, cents for USD 840 and EUR 978;
JPY 392 has none). The console never converts with float arithmetic:

- Display: the minor amount is turned into a decimal string (`20010` → `"200.10"`) with integer and
  string operations, and that string is passed to `Intl.NumberFormat`, which formats it exactly.
- Input: a partial capture is typed in major units and parsed as a string. `"200.10"` → `20010`,
  `"200.1"` → `20010`; `"1.234"` (too many decimals for INR), negatives, signs, separators and
  exponents are rejected. The result must be greater than zero and at most the authorized amount.

See `src/app/shared/money.ts`.

## Card numbers

The APIs return only masked PANs (first 6 + last 4) and tokens, and the console accepts nothing
more: the card filter takes exactly 4 digits, a merchant id that looks like a full PAN (12 to 19
digits passing Luhn) is refused, and any unmasked number reaching the display is masked.

## Notes on the API

- Results are always newest first; the search endpoint has no sort parameter, so the table does
  not offer one.
- Date filters are UTC days. The API's `from` is inclusive and `to` exclusive, so choosing
  18 to 18 October sends `from=2026-10-18T00:00:00Z&to=2026-10-19T00:00:00Z`.
