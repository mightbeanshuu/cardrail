import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTestHttp } from '../../../testing/test-providers';
import { ApiError } from '../../core/errors/api-error';
import { DEMO_MERCHANTS } from './merchants';
import { ReceiptComponent } from './receipt.component';
import { TerminalExchange } from './terminal.service';

function exchange(overrides: Partial<TerminalExchange> = {}): TerminalExchange {
  return {
    seq: 1,
    kind: 'new',
    originalSeq: null,
    card: { id: 'funded', label: 'Funded card', maskedPan: '555555******4444', standInLimitMinor: 500_000 },
    merchant: DEMO_MERCHANTS[0],
    amountMinor: 25000,
    currency: 356,
    terminalId: 'WEB12345',
    stan: '000041',
    rrn: '629109000041',
    transmissionDateTime: '1018091530',
    sentAt: '2026-10-18T09:15:30Z',
    latencyMs: 87,
    response: {
      id: '7b0d3c2e-3f7a-4d6e-9a1b-2c4d5e6f7a8b',
      approved: true,
      responseCode: '00',
      responseCodeName: 'APPROVED',
      authCode: '975250',
      standIn: false,
      maskedPan: '555555******4444',
      token: '9999990919957312',
    },
    error: null,
    ...overrides,
  };
}

describe('ReceiptComponent', () => {
  let fixture: ComponentFixture<ReceiptComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ReceiptComponent], providers: [...provideTestHttp(), provideRouter([])] });
    fixture = TestBed.createComponent(ReceiptComponent);
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const text = () => el().textContent!.replace(/\s+/g, ' ');

  async function show(x: TerminalExchange | null, original: TerminalExchange | null = null) {
    fixture.componentRef.setInput('exchange', x);
    fixture.componentRef.setInput('original', original);
    await fixture.whenStable();
  }

  it('tells the visitor what to do before anything is sent', async () => {
    await show(null);
    expect(text()).toContain('No request sent yet');
    expect(el().querySelector('button')).toBeNull();
  });

  it('prints an approval: code, label, auth code, masked card, token, keys and latency', async () => {
    await show(exchange());
    expect(el().querySelector('.receipt__code')?.textContent?.trim()).toBe('00');
    expect(el().querySelector('.receipt--approved')).not.toBeNull();
    const t = text();
    expect(t).toContain('Approved');
    expect(t).toContain('975250');
    expect(t).toContain('555555******4444');
    expect(t).toContain('9999990919957312');
    expect(t).toContain('₹250.00');
    expect(t).toContain('000041 · 629109000041');
    expect(t).toContain('87 ms');
    expect(t).toContain('Approved by the issuer');
    expect(el().querySelector('.badge--stip')).toBeNull();
    const link = el().querySelector<HTMLAnchorElement>('a.btn')!;
    expect(link.textContent?.trim()).toBe('Open authorization');
    expect(link.getAttribute('href')).toBe('/authorizations/7b0d3c2e-3f7a-4d6e-9a1b-2c4d5e6f7a8b');
  });

  it('marks a stand-in approval and explains it against the card range limit', async () => {
    await show(exchange({ amountMinor: 400_000, response: { ...exchange().response!, standIn: true } }));
    expect(el().querySelector('.badge--stip')?.textContent).toBe('STIP');
    expect(text()).toContain('Approved by stand-in processing');
    expect(text()).toContain('₹5,000.00');
  });

  it('explains a 91 above the stand-in limit', async () => {
    await show(
      exchange({
        amountMinor: 600_000,
        response: { ...exchange().response!, approved: false, responseCode: '91', authCode: null, standIn: true },
      }),
    );
    expect(el().querySelector('.receipt--system')).not.toBeNull();
    expect(text()).toContain('Issuer unavailable');
    expect(text()).toContain('above the stand-in limit of ₹5,000.00');
  });

  it('shows a 94 without an authorization link (nothing was stored)', async () => {
    await show(
      exchange({
        kind: 'conflicting',
        originalSeq: 1,
        seq: 2,
        response: { ...exchange().response!, id: null, approved: false, responseCode: '94', authCode: null, token: null },
      }),
    );
    expect(text()).toContain('94');
    expect(text()).toContain('Request #1 with a changed amount');
    expect(text()).toContain('different amount');
    expect(Array.from(el().querySelectorAll('a')).some((a) => a.textContent?.includes('Open authorization'))).toBe(false);
  });

  it('says a retransmission got the stored answer back when the ids match', async () => {
    const original = exchange();
    await show(exchange({ seq: 2, kind: 'retransmission', originalSeq: 1 }), original);
    expect(text()).toContain('Retransmission of request #1');
    expect(text()).toContain('same authorization id');
  });

  it('shows the rate-limit message for a 429', async () => {
    const error = new ApiError(429, 'Too many requests', 'Too many requests from your address. Wait a few seconds and try again.');
    await show(exchange({ response: null, error }));
    expect(text()).toContain('Too many requests');
    expect(text()).toContain('Wait a few seconds');
    expect(text()).toContain('HTTP 429');
  });

  it('emits the sequence number for both kinds of resend', async () => {
    await show(exchange({ seq: 3 }));
    const retransmits: number[] = [];
    const conflicts: number[] = [];
    fixture.componentInstance.retransmit.subscribe((s) => retransmits.push(s));
    fixture.componentInstance.conflicting.subscribe((s) => conflicts.push(s));
    const buttons = Array.from(el().querySelectorAll<HTMLButtonElement>('button'));
    buttons.find((b) => b.textContent?.includes('retransmission'))!.click();
    buttons.find((b) => b.textContent?.includes('amount +1.00'))!.click();
    expect(retransmits).toEqual([3]);
    expect(conflicts).toEqual([3]);
  });
});
