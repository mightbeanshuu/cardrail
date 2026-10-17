import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTestHttp } from '../../../testing/test-providers';
import { DEMO_CARDS } from '../../demo/demo-cards';
import { TerminalPageComponent } from './terminal-page.component';

const AUTH_URL = 'http://switch.test/v1/authorizations';
const FAULTS_URL = 'http://switch.test/dev/issuer/faults';

describe('TerminalPageComponent', () => {
  let fixture: ComponentFixture<TerminalPageComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [TerminalPageComponent],
      providers: [...provideTestHttp(), provideRouter([])],
    });
    fixture = TestBed.createComponent(TerminalPageComponent);
    http = TestBed.inject(HttpTestingController);
    await fixture.whenStable();
    http.expectOne(FAULTS_URL).flush({ latencyMs: 0, failureRate: 0 });
    await fixture.whenStable();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const text = () => el().textContent!.replace(/\s+/g, ' ');

  async function sendAndRespond(body: object) {
    el().querySelector('app-terminal-form form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    http.expectOne(AUTH_URL).flush(body);
    await fixture.whenStable();
  }

  it('starts with empty states that say what to do', () => {
    expect(text()).toContain('No request sent yet');
    expect(text()).toContain('Requests you send are listed here');
    expect(el().querySelector('.issuer-state')?.textContent).toContain('answering normally');
  });

  it('prints the response and adds it to the session history, never showing a full card number', async () => {
    await sendAndRespond({
      id: 'f00dfeed-0000-4000-8000-000000000001',
      approved: true,
      responseCode: '00',
      responseCodeName: 'APPROVED',
      authCode: '975250',
      standIn: false,
      maskedPan: '555555******4444',
      token: '9999990919957312',
    });
    expect(el().querySelector('.receipt__code')?.textContent?.trim()).toBe('00');
    const rows = el().querySelectorAll('table tbody tr');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('00 Approved');
    expect(rows[0].textContent).toContain('f00dfeed');
    const html = el().innerHTML;
    for (const card of DEMO_CARDS) expect(html).not.toContain(card.pan);
  });

  it('switches the issuer to slow from the stand-in panel', async () => {
    const button = Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => b.textContent?.trim() === 'Make the issuer slow',
    )!;
    button.click();
    const req = http.expectOne(FAULTS_URL);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ latencyMs: 3000, failureRate: 0 });
    req.flush({ latencyMs: 3000, failureRate: 0 });
    await fixture.whenStable();
    expect(text()).toContain('slow, 3,000 ms per call');
  });

  it('says so when the deployment has no issuer simulator', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [TerminalPageComponent], providers: [...provideTestHttp(), provideRouter([])] });
    const f = TestBed.createComponent(TerminalPageComponent);
    const h = TestBed.inject(HttpTestingController);
    await f.whenStable();
    h.expectOne(FAULTS_URL).flush({ title: 'Not Found', status: 404 }, { status: 404, statusText: 'Not Found' });
    await f.whenStable();
    expect((f.nativeElement as HTMLElement).textContent).toContain('not enabled on this deployment');
  });
});
