import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { PROBLEM_HEADERS, provideTestHttp } from '../../../testing/test-providers';
import { SettlementRun } from './clearing.model';
import { SettlementPageComponent } from './settlement-page.component';

describe('SettlementPageComponent', () => {
  const base = 'http://clearing.test/v1';
  let harness: RouterTestingHarness;
  let controller: HttpTestingController;

  const run: SettlementRun = {
    businessDate: '2026-10-18',
    status: 'COMPLETED',
    startedAt: '2026-10-18T23:00:00Z',
    finishedAt: '2026-10-18T23:00:02Z',
    recordCount: 1,
    positions: [
      { partyType: 'ISSUER', partyId: '555555', currency: 356, recordCount: 1, grossMinor: 20000, interchangeMinor: 300, netMinor: -19700 },
      { partyType: 'NETWORK', partyId: 'CARDRAIL', currency: 356, recordCount: 1, grossMinor: 20000, interchangeMinor: 300, netMinor: 0 },
      { partyType: 'ACQUIRER', partyId: '123456', currency: 356, recordCount: 1, grossMinor: 20000, interchangeMinor: 300, netMinor: 19700 },
    ],
  };

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [...provideTestHttp(), provideRouter([{ path: 'settlement', component: SettlementPageComponent }])],
    });
    controller = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/settlement?date=2026-10-18', SettlementPageComponent);
  });

  afterEach(() => controller.verify());

  const el = () => harness.routeNativeElement as HTMLElement;

  function flushRecords(): void {
    controller.expectOne((r) => r.url === `${base}/clearing-records` && r.params.get('date') === '2026-10-18').flush({
      items: [],
      page: 0,
      size: 50,
      total: 0,
    });
  }

  it('says when no run exists for the date', async () => {
    controller
      .expectOne(`${base}/settlement-runs/2026-10-18`)
      .flush({ title: 'Settlement run not found', status: 404 }, { status: 404, statusText: 'Not Found', headers: PROBLEM_HEADERS });
    flushRecords();
    await harness.fixture.whenStable();
    expect(el().textContent).toContain('No settlement run for this date yet.');
    expect(el().textContent).toContain('No clearing records for this date.');
  });

  it('shows net positions in party order with a zero reconciliation total', async () => {
    controller.expectOne(`${base}/settlement-runs/2026-10-18`).flush(run);
    flushRecords();
    await harness.fixture.whenStable();

    const rows = Array.from(el().querySelectorAll('section[aria-labelledby=positions-h] tbody tr'));
    expect(rows.map((r) => r.querySelector('td')?.textContent?.trim())).toEqual(['Acquirer', 'Issuer BIN', 'Network']);
    expect(rows[0].textContent).toContain('₹197.00');
    expect(rows[0].textContent).toContain('Receives');
    expect(rows[1].textContent).toContain('-₹197.00');
    expect(rows[1].textContent).toContain('Pays');
    const foot = el().querySelector('tfoot')!.textContent!.replace(/\s+/g, ' ');
    expect(foot).toContain('Reconciliation total (INR)');
    expect(foot).toContain('₹0.00');
    expect(foot).toContain('Balanced');
  });

  it('flags an out-of-balance run', async () => {
    controller.expectOne(`${base}/settlement-runs/2026-10-18`).flush({
      ...run,
      positions: [...run.positions.slice(0, 2), { ...run.positions[2], netMinor: 19701 }],
    });
    flushRecords();
    await harness.fixture.whenStable();
    expect(el().textContent).toContain('Out of balance');
    expect(el().querySelector('tfoot')!.textContent).toContain('Not zero');
  });
});
