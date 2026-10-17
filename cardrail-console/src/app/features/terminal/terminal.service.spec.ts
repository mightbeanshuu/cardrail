import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { PROBLEM_HEADERS, provideTestHttp } from '../../../testing/test-providers';
import { ErrorBannerService } from '../../core/errors/error-banner.service';
import { DEMO_CARDS } from '../../demo/demo-cards';
import { DEMO_MERCHANTS } from './merchants';
import { TerminalRequestBuilder } from './terminal-request';
import { TERMINAL_CLOCK, TERMINAL_REQUEST_BUILDER, TerminalService } from './terminal.service';

const URL = 'http://switch.test/v1/authorizations';
const APPROVED = {
  id: 'a1',
  approved: true,
  responseCode: '00',
  responseCodeName: 'APPROVED',
  authCode: '123456',
  standIn: false,
  maskedPan: '555555******4444',
  token: '9999990000000001',
};

describe('TerminalService', () => {
  let service: TerminalService;
  let http: HttpTestingController;
  let clock: number;

  beforeEach(() => {
    clock = 1000;
    TestBed.configureTestingModule({
      providers: [
        ...provideTestHttp(),
        {
          provide: TERMINAL_REQUEST_BUILDER,
          useValue: new TerminalRequestBuilder('WEB12345', 100, () => new Date('2026-10-18T09:15:30Z')),
        },
        { provide: TERMINAL_CLOCK, useValue: () => clock },
      ],
    });
    service = TestBed.inject(TerminalService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const input = { card: DEMO_CARDS[0], merchant: DEMO_MERCHANTS[0], amountMinor: 25000 };

  it('POSTs the built request and measures latency in the browser', async () => {
    const result = firstValueFrom(service.send(input));
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toMatchObject({ pan: '5555555555554444', stan: '000100', terminalId: 'WEB12345', amountMinor: 25000 });
    clock += 142.4;
    req.flush(APPROVED);
    const x = await result;
    expect(x.latencyMs).toBe(142);
    expect(x.response?.authCode).toBe('123456');
    expect(x.kind).toBe('new');
    expect(service.history()).toEqual([x]);
  });

  it('keeps the PAN out of the exchange it hands to the UI', async () => {
    const result = firstValueFrom(service.send(input));
    http.expectOne(URL).flush(APPROVED);
    const x = await result;
    expect(JSON.stringify(x)).not.toContain('5555555555554444');
    expect(x.card.maskedPan).toBe('555555******4444');
  });

  it('retransmits the identical body, then a conflicting one with the same key', async () => {
    const first = firstValueFrom(service.send(input));
    const original = http.expectOne(URL);
    original.flush(APPROVED);
    const x = await first;

    const again = firstValueFrom(service.retransmit(x.seq));
    const repeat = http.expectOne(URL);
    expect(repeat.request.body).toEqual(original.request.body);
    repeat.flush(APPROVED);
    expect((await again).kind).toBe('retransmission');

    const conflicting = firstValueFrom(service.sendConflicting(x.seq));
    const changed = http.expectOne(URL);
    expect(changed.request.body.amountMinor).toBe(25100);
    expect(changed.request.body.stan).toBe(original.request.body.stan);
    expect(changed.request.body.transmissionDateTime).toBe(original.request.body.transmissionDateTime);
    changed.flush({ ...APPROVED, id: null, approved: false, responseCode: '94', authCode: null });
    const c = await conflicting;
    expect(c.response?.responseCode).toBe('94');
    expect(c.originalSeq).toBe(x.seq);

    expect(service.history().map((h) => h.kind)).toEqual(['conflicting', 'retransmission', 'new']);
  });

  it('records a 429 inline without raising the global banner', async () => {
    const result = firstValueFrom(service.send(input));
    http.expectOne(URL).flush({ title: 'Too Many Requests', status: 429 }, { status: 429, statusText: 'x', headers: PROBLEM_HEADERS });
    const x = await result;
    expect(x.response).toBeNull();
    expect(x.error?.isRateLimited).toBe(true);
    expect(x.error?.detail).toContain('Wait a few seconds');
    expect(TestBed.inject(ErrorBannerService).entries()).toEqual([]);
  });

  it('gives each new request a new STAN', async () => {
    for (let i = 0; i < 3; i++) {
      const r = firstValueFrom(service.send(input));
      http.expectOne(URL).flush(APPROVED);
      await r;
    }
    expect(service.history().map((h) => h.stan)).toEqual(['000102', '000101', '000100']);
  });
});
