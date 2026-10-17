import { HttpClient } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { TEST_CONFIG, provideTestHttp } from '../../../testing/test-providers';
import { ErrorBannerService } from '../errors/error-banner.service';
import { WARM_UP_OPTIONS, WarmUpOptions, WarmUpService } from './warm-up.service';
import { serviceFor, warmUpInterceptor } from './warm-up.interceptor';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { APP_CONFIG } from '../config/app-config';

const SWITCH_HEALTH = TEST_CONFIG.warmUp.switchHealthUrl;
const CLEARING_HEALTH = TEST_CONFIG.warmUp.clearingHealthUrl;

/** Small real timings so a whole warm-up runs in milliseconds. */
const FAST: WarmUpOptions = {
  deadlineMs: 400,
  attemptTimeoutMs: 40,
  initialDelayMs: 5,
  maxDelayMs: 20,
  slowAfterMs: 10_000,
  now: () => Date.now(),
  sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
};

const settle = (ms = 0) => new Promise((r) => setTimeout(r, ms));

describe('WarmUpService', () => {
  let service: WarmUpService;
  let http: HttpTestingController;

  function setup(options: Partial<WarmUpOptions> = {}) {
    TestBed.configureTestingModule({
      providers: [
        ...provideTestHttp({ warmUp: { ...TEST_CONFIG.warmUp, enabled: true } }),
        { provide: WARM_UP_OPTIONS, useValue: { ...FAST, ...options } },
      ],
    });
    service = TestBed.inject(WarmUpService);
    http = TestBed.inject(HttpTestingController);
  }

  it('pings both health endpoints and becomes ready when they answer', async () => {
    setup();
    service.start();
    expect(service.services().map((s) => s.status)).toEqual(['checking', 'checking']);
    http.expectOne(SWITCH_HEALTH).flush({ status: 'UP' });
    http.expectOne(CLEARING_HEALTH).flush({ status: 'UP' });
    await firstValueFrom(service.whenSettled('switch'));
    await firstValueFrom(service.whenSettled('clearing'));
    expect(service.allReady()).toBe(true);
    expect(service.waking()).toBe(false);
  });

  it('is idempotent: a second start sends no more pings', () => {
    setup();
    service.start();
    service.start();
    expect(http.match(SWITCH_HEALTH).length).toBe(1);
  });

  it('retries a failing service with backoff and shows it as waking, without the error banner', async () => {
    setup();
    service.start();
    http.expectOne(CLEARING_HEALTH).flush({ status: 'UP' });
    http.expectOne(SWITCH_HEALTH).flush('waking', { status: 503, statusText: 'Service Unavailable' });
    await settle(15);
    expect(service.status('switch')).toBe('waking');
    http.expectOne(SWITCH_HEALTH).flush({ status: 'UP' });
    await firstValueFrom(service.whenSettled('switch'));
    expect(service.status('switch')).toBe('ready');
    expect(service.services().find((s) => s.id === 'switch')?.attempts).toBe(2);
    expect(TestBed.inject(ErrorBannerService).entries()).toEqual([]);
  });

  it('abandons an attempt that hangs past its timeout and gives up at the deadline', async () => {
    setup({ deadlineMs: 120, attemptTimeoutMs: 30 });
    service.start();
    http.expectOne(CLEARING_HEALTH).flush({ status: 'UP' });
    const first = http.expectOne(SWITCH_HEALTH);
    await settle(45);
    expect(first.cancelled).toBe(true);
    // Leave every later attempt hanging too.
    await firstValueFrom(service.whenSettled('switch'));
    expect(service.status('switch')).toBe('failed');
    expect(service.failed()).toBe(true);
    http.match(SWITCH_HEALTH);
  });

  it('starts over on retry after a failure', async () => {
    setup({ deadlineMs: 30, attemptTimeoutMs: 10 });
    service.start();
    http.expectOne(CLEARING_HEALTH).flush({ status: 'UP' });
    await firstValueFrom(service.whenSettled('switch'));
    http.match(SWITCH_HEALTH);
    expect(service.status('switch')).toBe('failed');

    service.retry();
    expect(service.status('switch')).toBe('checking');
    http.match(CLEARING_HEALTH).forEach((r) => r.flush({ status: 'UP' }));
    http.expectOne(SWITCH_HEALTH).flush({ status: 'UP' });
    await firstValueFrom(service.whenSettled('switch'));
    expect(service.allReady()).toBe(true);
  });

  it('marks a slow first check as waking so the banner appears', async () => {
    setup({ slowAfterMs: 5 });
    service.start();
    await settle(15);
    expect(service.waking()).toBe(true);
    http.expectOne(SWITCH_HEALTH).flush({});
    http.expectOne(CLEARING_HEALTH).flush({});
  });

  it('does nothing when warm-up is disabled', async () => {
    TestBed.configureTestingModule({ providers: provideTestHttp() });
    service = TestBed.inject(WarmUpService);
    http = TestBed.inject(HttpTestingController);
    service.start();
    http.expectNone(SWITCH_HEALTH);
    await firstValueFrom(service.whenSettled('switch'));
  });
});

describe('warmUpInterceptor', () => {
  it('maps request URLs to the service they go to', () => {
    expect(serviceFor('https://sw.example/v1/authorizations', 'https://sw.example', 'https://cl.example/v1')).toBe('switch');
    expect(serviceFor('https://cl.example/v1/settlement-runs', 'https://sw.example', 'https://cl.example/v1')).toBe('clearing');
    // Dev proxy: empty switch base, clearing under /clearing.
    expect(serviceFor('/clearing/clearing-records', '', '/clearing')).toBe('clearing');
    expect(serviceFor('/v1/audit/verify', '', '/clearing')).toBe('switch');
    expect(serviceFor('https://elsewhere.example/x', 'https://sw.example', 'https://cl.example/v1')).toBeNull();
  });

  it('holds an API request until the switch has woken up', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([warmUpInterceptor])),
        provideHttpClientTesting(),
        { provide: APP_CONFIG, useValue: { ...TEST_CONFIG, warmUp: { ...TEST_CONFIG.warmUp, enabled: true } } },
        { provide: WARM_UP_OPTIONS, useValue: FAST },
      ],
    });
    const warmUp = TestBed.inject(WarmUpService);
    const http = TestBed.inject(HttpTestingController);
    warmUp.start();
    let done = false;
    TestBed.inject(HttpClient)
      .get(`${TEST_CONFIG.switchBaseUrl}/v1/audit/verify`)
      .subscribe(() => (done = true));
    await settle();
    http.expectNone(`${TEST_CONFIG.switchBaseUrl}/v1/audit/verify`);
    http.expectOne(SWITCH_HEALTH).flush({ status: 'UP' });
    http.expectOne(CLEARING_HEALTH).flush({ status: 'UP' });
    await settle();
    http.expectOne(`${TEST_CONFIG.switchBaseUrl}/v1/audit/verify`).flush({ intact: true, recordsVerified: 1 });
    expect(done).toBe(true);
  });
});
