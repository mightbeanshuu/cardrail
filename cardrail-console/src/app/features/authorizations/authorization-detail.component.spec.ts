import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { authorization } from '../../../testing/fixtures';
import { PROBLEM_HEADERS, provideTestHttp } from '../../../testing/test-providers';
import { AuthorizationDetailComponent } from './authorization-detail.component';

describe('AuthorizationDetailComponent', () => {
  const url = 'http://switch.test/v1/authorizations/7b0d3c2e-3f7a-4d6e-9a1b-2c4d5e6f7a8b';
  let harness: RouterTestingHarness;
  let controller: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        ...provideTestHttp(),
        provideRouter([{ path: 'authorizations/:id', component: AuthorizationDetailComponent }], withComponentInputBinding()),
      ],
    });
    controller = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/authorizations/7b0d3c2e-3f7a-4d6e-9a1b-2c4d5e6f7a8b', AuthorizationDetailComponent);
  });

  afterEach(() => controller.verify());

  const el = () => harness.routeNativeElement as HTMLElement;
  const reverseButton = () =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim().startsWith('Revers'))!;

  async function load(overrides = {}) {
    controller.expectOne(url).flush(authorization(overrides));
    await harness.fixture.whenStable();
  }

  it('shows all fields and a timeline', async () => {
    await load();
    const text = el().textContent!.replace(/\s+/g, ' ');
    for (const s of ['₹250.00', '00 Approved', 'A1B2C3', '555555******4444', 'MERCHANT000001', '000123', '629112000123', 'TERM0001']) {
      expect(text).toContain(s);
    }
    expect(el().querySelectorAll('.timeline li').length).toBe(2);
  });

  it('disables reverse and capture unless AUTHORIZED', async () => {
    await load({ status: 'CAPTURED', capturedAmountMinor: 25000, capturedAt: '2026-10-18T10:00:00Z' });
    expect(reverseButton().disabled).toBe(true);
    expect(el().querySelector<HTMLInputElement>('#capture-amount')!.disabled).toBe(true);
    expect(el().textContent).toContain('Only authorizations in status Authorized can be reversed or captured');
  });

  it('reverses after confirmation, reloads and reports the outcome', async () => {
    await load();
    const trigger = reverseButton();
    expect(trigger.disabled).toBe(false);
    trigger.click();
    await harness.fixture.whenStable();

    const dialog = el().querySelector('dialog')!;
    expect(dialog.textContent).toContain('₹250.00');
    expect(document.activeElement?.textContent?.trim()).toBe('Cancel');
    const confirm = Array.from(dialog.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Reverse')!;
    confirm.click();
    await harness.fixture.whenStable();

    const post = controller.expectOne(`${url}/reversal`);
    expect(post.request.method).toBe('POST');
    post.flush({ authorizationId: 'x', outcome: 'REVERSED', status: 'REVERSED', releasedMinor: 25000, reversedAt: '2026-10-18T10:00:00Z' });
    await harness.fixture.whenStable();
    await load({ status: 'REVERSED', reversedAt: '2026-10-18T10:00:00Z' });

    expect(el().querySelector('[role=status].banner')?.textContent).toContain('Reversed. ₹250.00 released.');
    expect(reverseButton().disabled).toBe(true);
  });

  it('cancelling the dialog sends nothing and returns focus to the trigger', async () => {
    await load();
    const trigger = reverseButton();
    trigger.focus();
    trigger.click();
    await harness.fixture.whenStable();
    const cancel = Array.from(el().querySelectorAll('dialog button')).find((b) => b.textContent?.trim() === 'Cancel') as HTMLButtonElement;
    cancel.click();
    await harness.fixture.whenStable();
    expect(document.activeElement).toBe(trigger);
    controller.expectNone(`${url}/reversal`);
  });

  it('captures a partial amount in minor units', async () => {
    await load();
    const input = el().querySelector<HTMLInputElement>('#capture-amount')!;
    input.value = '200.10';
    input.dispatchEvent(new Event('input'));
    input.closest('form')!.dispatchEvent(new Event('submit'));
    await harness.fixture.whenStable();
    const post = controller.expectOne(`${url}/capture`);
    expect(post.request.body).toEqual({ amountMinor: 20010 });
    post.flush({ authorizationId: 'x', capturedMinor: 20010, releasedMinor: 4990, capturedAt: '2026-10-18T10:00:00Z' });
    await harness.fixture.whenStable();
    await load({ status: 'CAPTURED', capturedAmountMinor: 20010, capturedAt: '2026-10-18T10:00:00Z' });
    expect(el().textContent).toContain('Captured ₹200.10. ₹49.90 released.');
  });

  it('shows a not-found state for an unknown id', async () => {
    controller.expectOne(url).flush({ title: 'Authorization not found', status: 404 }, { status: 404, statusText: 'Not Found', headers: PROBLEM_HEADERS });
    await harness.fixture.whenStable();
    expect(el().textContent).toContain('No authorization with id');
  });
});
