import { HttpClient } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { PROBLEM_HEADERS, provideTestHttp } from '../../../testing/test-providers';
import { ApiError } from '../errors/api-error';
import { ErrorBannerService } from '../errors/error-banner.service';
import { CORRELATION_ID_HEADER } from './correlation-id.interceptor';
import { handledStatuses } from './http-context';

describe('HTTP interceptors', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  let banner: ErrorBannerService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: provideTestHttp() });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
    banner = TestBed.inject(ErrorBannerService);
  });

  afterEach(() => controller.verify());

  async function failWith(
    respond: (req: ReturnType<HttpTestingController['expectOne']>) => void,
    options: Parameters<HttpClient['get']>[1] = {},
  ): Promise<ApiError> {
    const result = firstValueFrom(http.get('/x', options)).then(
      () => {
        throw new Error('expected an error');
      },
      (e: unknown) => e,
    );
    respond(controller.expectOne('/x'));
    const error = await result;
    expect(error).toBeInstanceOf(ApiError);
    return error as ApiError;
  }

  describe('correlation id', () => {
    it('adds an X-Correlation-Id the switch will accept to every request', () => {
      http.get('/a').subscribe();
      http.post('/b', {}).subscribe();
      const a = controller.expectOne('/a').request.headers.get(CORRELATION_ID_HEADER);
      const b = controller.expectOne('/b').request.headers.get(CORRELATION_ID_HEADER);
      expect(a).toMatch(/^[A-Za-z0-9._-]{1,64}$/);
      expect(b).toMatch(/^[A-Za-z0-9._-]{1,64}$/);
      expect(a).not.toBe(b);
    });

    it('keeps an id the caller already set', () => {
      http.get('/a', { headers: { [CORRELATION_ID_HEADER]: 'given-id' } }).subscribe();
      expect(controller.expectOne('/a').request.headers.get(CORRELATION_ID_HEADER)).toBe('given-id');
    });
  });

  describe('problem+json mapping', () => {
    it('maps an RFC 7807 body to a typed ApiError and shows it in the banner', async () => {
      const error = await failWith((req) =>
        req.flush(
          {
            type: 'about:blank',
            title: 'Not allowed in the authorization\'s current state',
            status: 409,
            detail: 'authorization 7b0d is REVERSED',
            instance: '/v1/authorizations/7b0d/capture',
          },
          { status: 409, statusText: 'Conflict', headers: { ...PROBLEM_HEADERS, [CORRELATION_ID_HEADER]: 'cid-from-server' } },
        ),
      );
      expect(error.status).toBe(409);
      expect(error.title).toBe("Not allowed in the authorization's current state");
      expect(error.detail).toBe('authorization 7b0d is REVERSED');
      expect(error.type).toBeNull();
      expect(error.instance).toBe('/v1/authorizations/7b0d/capture');
      expect(error.correlationId).toBe('cid-from-server');
      expect(banner.entries().map((e) => e.error)).toEqual([error]);
    });

    it('keeps validation field errors', async () => {
      const error = await failWith((req) =>
        req.flush(
          { title: 'Invalid request', status: 400, detail: 'One or more fields are invalid.', errors: ['amountMinor: must be greater than 0'] },
          { status: 400, statusText: 'Bad Request', headers: PROBLEM_HEADERS },
        ),
      );
      expect(error.fieldErrors).toEqual(['amountMinor: must be greater than 0']);
    });

    it('falls back to the request correlation id when the response has none', async () => {
      const error = await failWith((req) => req.flush({ title: 'Internal error', status: 500 }, { status: 500, statusText: 'x' }));
      expect(error.correlationId).toMatch(/^[A-Za-z0-9._-]+$/);
    });

    it('parses problem+json delivered as a Blob (blob responseType)', async () => {
      const blob = new Blob([JSON.stringify({ title: 'Settlement run not found', status: 404, detail: 'no run for 2026-10-18' })], {
        type: 'application/problem+json',
      });
      const error = await failWith((req) => req.flush(blob, { status: 404, statusText: 'Not Found' }), {
        responseType: 'blob' as 'json',
      });
      expect(error.title).toBe('Settlement run not found');
      expect(error.detail).toBe('no run for 2026-10-18');
    });

    it('maps network failures to status 0', async () => {
      const error = await failWith((req) => req.error(new ProgressEvent('error')));
      expect(error.status).toBe(0);
      expect(error.title).toBe('Service unreachable');
    });

    it('maps non-problem bodies by status', async () => {
      const error = await failWith((req) => req.flush('upstream down', { status: 502, statusText: 'Bad Gateway' }));
      expect(error.status).toBe(502);
      expect(error.title).toBe('Bad gateway');
      expect(error.detail).toBe('upstream down');
    });

    it('does not raise the banner for statuses the caller handles', async () => {
      const error = await failWith(
        (req) => req.flush({ title: 'Authorization not found', status: 404 }, { status: 404, statusText: 'Not Found', headers: PROBLEM_HEADERS }),
        { context: handledStatuses(404) },
      );
      expect(error.isNotFound).toBe(true);
      expect(banner.entries()).toEqual([]);
    });

    it('folds repeats of the same error into one banner entry', async () => {
      for (let i = 0; i < 2; i++) {
        await failWith((req) => req.flush({ title: 'Internal error', status: 500 }, { status: 500, statusText: 'x' }));
      }
      expect(banner.entries().length).toBe(1);
      expect(banner.entries()[0].count).toBe(2);
    });
  });
});
