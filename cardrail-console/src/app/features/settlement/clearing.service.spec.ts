import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { PROBLEM_HEADERS, provideTestHttp } from '../../../testing/test-providers';
import { ApiError } from '../../core/errors/api-error';
import { ErrorBannerService } from '../../core/errors/error-banner.service';
import { reconcile } from './clearing.model';
import { ClearingService } from './clearing.service';

describe('ClearingService', () => {
  const base = 'http://clearing.test/v1';
  let service: ClearingService;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: provideTestHttp() });
    service = TestBed.inject(ClearingService);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  it('lists clearing records for a date with paging', () => {
    service.records('2026-10-18', 1, 50).subscribe();
    const req = controller.expectOne((r) => r.url === `${base}/clearing-records`);
    expect(req.request.params.get('date')).toBe('2026-10-18');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('50');
    req.flush({ items: [], page: 1, size: 50, total: 0 });
  });

  it('posts a settlement run with the business date', () => {
    service.run('2026-10-18').subscribe();
    const req = controller.expectOne(`${base}/settlement-runs`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ businessDate: '2026-10-18' });
    req.flush({ businessDate: '2026-10-18', status: 'COMPLETED', positions: [] });
  });

  it('returns null when no run exists, without a banner', async () => {
    const result = firstValueFrom(service.getRun('2026-10-18'));
    controller
      .expectOne(`${base}/settlement-runs/2026-10-18`)
      .flush({ title: 'Not found', status: 404 }, { status: 404, statusText: 'Not Found', headers: PROBLEM_HEADERS });
    expect(await result).toBeNull();
    expect(TestBed.inject(ErrorBannerService).entries()).toEqual([]);
  });

  it('propagates other run errors as ApiError', async () => {
    const result = firstValueFrom(service.getRun('2026-10-18')).catch((e: unknown) => e);
    controller
      .expectOne(`${base}/settlement-runs/2026-10-18`)
      .flush({ title: 'Internal error', status: 500 }, { status: 500, statusText: 'x', headers: PROBLEM_HEADERS });
    expect(await result).toBeInstanceOf(ApiError);
  });

  it('downloads the CSV report as a Blob', async () => {
    const result = firstValueFrom(service.reportCsv('2026-10-18'));
    const req = controller.expectOne(`${base}/settlement-runs/2026-10-18/report.csv`);
    expect(req.request.responseType).toBe('blob');
    expect(req.request.headers.get('Accept')).toBe('text/csv');
    req.flush(new Blob(['party,net\n'], { type: 'text/csv' }));
    expect(await result).toBeInstanceOf(Blob);
  });
});

describe('reconcile', () => {
  it('sums net positions per currency and requires exactly zero', () => {
    const totals = reconcile([
      { partyType: 'ACQUIRER', partyId: '123456', currency: 356, netMinor: 19800 },
      { partyType: 'ISSUER', partyId: '555555', currency: 356, netMinor: -20000 },
      { partyType: 'NETWORK', partyId: 'CARDRAIL', currency: 356, netMinor: 200 },
      { partyType: 'ACQUIRER', partyId: '123456', currency: 840, netMinor: 100 },
      { partyType: 'ISSUER', partyId: '411111', currency: 840, netMinor: -99 },
    ]);
    expect(totals).toEqual([
      { currency: 356, totalMinor: 0n, balanced: true },
      { currency: 840, totalMinor: 1n, balanced: false },
    ]);
  });
});
