import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideTestHttp } from '../../../testing/test-providers';
import { CaptureFormComponent } from './capture-form.component';

describe('CaptureFormComponent', () => {
  let fixture: ComponentFixture<CaptureFormComponent>;
  let emitted: (number | null)[];

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [CaptureFormComponent], providers: provideTestHttp() });
    fixture = TestBed.createComponent(CaptureFormComponent);
    fixture.componentRef.setInput('authorizedMinor', 25000);
    fixture.componentRef.setInput('currency', 356);
    emitted = [];
    fixture.componentInstance.capture.subscribe((v) => emitted.push(v));
    await fixture.whenStable();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const input = () => el().querySelector<HTMLInputElement>('#capture-amount')!;
  const button = () => el().querySelector<HTMLButtonElement>('button[type=submit]')!;
  const error = () => el().querySelector('#capture-amount-err')?.textContent?.trim() ?? null;

  async function enter(value: string): Promise<void> {
    input().value = value;
    input().dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  async function submit(): Promise<void> {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('labels the input with the currency and shows the full amount as the default', () => {
    expect(el().querySelector('label[for=capture-amount]')?.textContent).toContain('INR');
    expect(input().placeholder).toBe('250.00');
    expect(el().textContent).toContain('Leave empty to capture the full ₹250.00');
  });

  it('emits null for a full capture when the amount is empty', async () => {
    await submit();
    expect(emitted).toEqual([null]);
    expect(error()).toBeNull();
  });

  it('emits minor units for a partial capture ("200.10" → 20010)', async () => {
    await enter('200.10');
    await submit();
    expect(emitted).toEqual([20010]);
  });

  it('accepts exactly the authorized amount', async () => {
    await enter('250');
    await submit();
    expect(emitted).toEqual([25000]);
  });

  it.each([
    ['0', 'Amount must be greater than zero.'],
    ['0.00', 'Amount must be greater than zero.'],
    ['-5', 'Amount cannot be negative.'],
    ['1.234', 'Use at most 2 decimal places.'],
    ['12,50', 'Use digits and an optional dot, for example 200.10.'],
    ['250.01', 'Cannot exceed the authorized ₹250.00.'],
  ])('rejects %s', async (value, message) => {
    await enter(value);
    await submit();
    expect(emitted).toEqual([]);
    expect(error()).toBe(message);
    expect(input().getAttribute('aria-invalid')).toBe('true');
    expect(input().getAttribute('aria-describedby')).toContain('capture-amount-err');
  });

  it('shows the error as soon as the field is edited, before submit', async () => {
    await enter('1.234');
    expect(error()).toBe('Use at most 2 decimal places.');
  });

  it('is disabled when the authorization cannot be captured', async () => {
    fixture.componentRef.setInput('disabledReason', 'Already captured.');
    await fixture.whenStable();
    expect(input().disabled).toBe(true);
    expect(button().disabled).toBe(true);
    await submit();
    expect(emitted).toEqual([]);
  });

  it('uses the currency exponent: JPY has no minor units', async () => {
    fixture.componentRef.setInput('currency', 392);
    fixture.componentRef.setInput('authorizedMinor', 1500);
    await fixture.whenStable();
    await enter('10.5');
    await submit();
    expect(error()).toBe('This currency has no minor units; enter a whole number.');
    await enter('1000');
    await submit();
    expect(emitted).toEqual([1000]);
  });
});
