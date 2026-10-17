import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { authorization } from '../../../testing/fixtures';
import { PROBLEM_HEADERS, provideTestHttp } from '../../../testing/test-providers';
import { ApiError } from '../../core/errors/api-error';
import { ErrorBannerService } from '../../core/errors/error-banner.service';
import { DEFAULT_QUERY } from './authorization-query';
import { AuthorizationsService } from './authorizations.service';

describe('AuthorizationsService', () => {
  let service: AuthorizationsService;
  let controller: HttpTestingController;
  const base = 'http://switch.test/v1/authorizations';

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: provideTestHttp() });
    service = TestBed.inject(AuthorizationsService);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  it('searches with only paging when no filters are set', () => {
    service.search(DEFAULT_QUERY).subscribe();
    const req = controller.expectOne((r) => r.url === base);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys().sort()).toEqual(['page', 'size']);
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    req.flush({ items: [], page: 0, size: 20, total: 0 });
  });

  it('builds every filter query parameter from the contract', async () => {
    const result = firstValueFrom(
      service.search({
        filter: {
          status: 'DECLINED',
          responseCode: '51',
          merchantId: 'MERCHANT000001',
          last4: '4444',
          standIn: true,
          from: '2026-10-01',
          to: '2026-10-18',
        },
        page: 2,
        size: 50,
      }),
    );
    const req = controller.expectOne((r) => r.url === base);
    const p = req.request.params;
    expect(p.get('status')).toBe('DECLINED');
    expect(p.get('responseCode')).toBe('51');
    expect(p.get('merchantId')).toBe('MERCHANT000001');
    expect(p.get('last4')).toBe('4444');
    expect(p.get('standIn')).toBe('true');
    expect(p.get('from')).toBe('2026-10-01T00:00:00Z');
    // `to` is exclusive on the API, so the last chosen day ends at the next midnight.
    expect(p.get('to')).toBe('2026-10-19T00:00:00Z');
    expect(p.get('page')).toBe('2');
    expect(p.get('size')).toBe('50');
    const page = { items: [authorization()], page: 2, size: 50, total: 101 };
    req.flush(page);
    expect(await result).toEqual(page);
  });

  it('sends standIn=false explicitly and rolls a month-end `to` into the next month', () => {
    service.search({ ...DEFAULT_QUERY, filter: { standIn: false, from: '2026-10-31', to: '2026-10-31' } }).subscribe();
    const req = controller.expectOne((r) => r.url === base);
    expect(req.request.params.get('standIn')).toBe('false');
    expect(req.request.params.get('from')).toBe('2026-10-31T00:00:00Z');
    expect(req.request.params.get('to')).toBe('2026-11-01T00:00:00Z');
    req.flush({ items: [], page: 0, size: 20, total: 0 });
  });

  it('gets one authorization and reports 404 to the caller without a banner', async () => {
    const result = firstValueFrom(service.get('abc')).catch((e: unknown) => e);
    controller
      .expectOne(`${base}/abc`)
      .flush({ title: 'Authorization not found', status: 404, detail: 'abc' }, { status: 404, statusText: 'Not Found', headers: PROBLEM_HEADERS });
    const error = (await result) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.isNotFound).toBe(true);
    expect(TestBed.inject(ErrorBannerService).entries()).toEqual([]);
  });

  it('posts a reversal', async () => {
    const result = firstValueFrom(service.reverse('id-1'));
    const req = controller.expectOne(`${base}/id-1/reversal`);
    expect(req.request.method).toBe('POST');
    req.flush({ authorizationId: 'id-1', outcome: 'REVERSED', status: 'REVERSED', releasedMinor: 25000, reversedAt: '2026-10-18T10:00:00Z' });
    expect((await result).releasedMinor).toBe(25000);
  });

  it('posts a partial capture with amountMinor', () => {
    service.capture('id-1', 20010).subscribe();
    const req = controller.expectOne(`${base}/id-1/capture`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ amountMinor: 20010 });
    req.flush({ authorizationId: 'id-1', capturedMinor: 20010, releasedMinor: 4990, capturedAt: '2026-10-18T10:00:00Z' });
  });

  it('posts a full capture without amountMinor', () => {
    service.capture('id-1', null).subscribe();
    const req = controller.expectOne(`${base}/id-1/capture`);
    expect(req.request.body).toEqual({});
    req.flush({ authorizationId: 'id-1', capturedMinor: 25000, releasedMinor: 0, capturedAt: '2026-10-18T10:00:00Z' });
  });

  it('maps a 422 capture problem to ApiError and shows it in the banner', async () => {
    const result = firstValueFrom(service.capture('id-1', 99999999)).catch((e: unknown) => e);
    controller
      .expectOne(`${base}/id-1/capture`)
      .flush(
        { title: 'Invalid capture amount', status: 422, detail: 'capture exceeds authorized amount' },
        { status: 422, statusText: 'Unprocessable Entity', headers: PROBLEM_HEADERS },
      );
    const error = (await result) as ApiError;
    expect(error.status).toBe(422);
    expect(error.title).toBe('Invalid capture amount');
    expect(TestBed.inject(ErrorBannerService).entries()[0].error).toBe(error);
  });
});
