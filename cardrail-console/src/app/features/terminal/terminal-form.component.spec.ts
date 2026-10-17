import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideTestHttp } from '../../../testing/test-providers';
import { DEMO_CARDS } from '../../demo/demo-cards';
import { TerminalFormComponent } from './terminal-form.component';
import { TerminalInput } from './terminal-request';

describe('TerminalFormComponent', () => {
  let fixture: ComponentFixture<TerminalFormComponent>;
  let sent: TerminalInput[];

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [TerminalFormComponent], providers: provideTestHttp() });
    fixture = TestBed.createComponent(TerminalFormComponent);
    sent = [];
    fixture.componentInstance.send.subscribe((v) => sent.push(v));
    await fixture.whenStable();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const amount = () => el().querySelector<HTMLInputElement>('#t-amount')!;

  async function setAmount(value: string) {
    amount().value = value;
    amount().dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  async function submit() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('offers one radio per demo card, labelled with the masked number', () => {
    const options = Array.from(el().querySelectorAll('.card-option'));
    expect(options.length).toBe(DEMO_CARDS.length);
    expect(options[0].textContent).toContain('555555******4444');
    expect(options[0].textContent).toContain('Funded card');
    expect(el().textContent).toContain('Low balance → 51');
    expect(el().textContent).toContain('Blocked → 05');
  });

  it('never renders a full card number and has no field where one could be typed', () => {
    const html = el().innerHTML;
    for (const card of DEMO_CARDS) expect(html).not.toContain(card.pan);
    const textInputs = Array.from(el().querySelectorAll('input:not([type=radio])'));
    expect(textInputs.map((i) => i.id)).toEqual(['t-amount']);
    expect(amount().maxLength).toBeLessThan(15);
  });

  it('lists the merchant categories, with betting marked as blocked', () => {
    const options = Array.from(el().querySelectorAll('#t-merchant option')).map((o) => o.textContent?.trim());
    expect(options).toEqual([
      'Grocery · 5411',
      'Fuel · 5541',
      'Restaurant · 5812',
      'Airline · 4511',
      'Betting · 7995 (blocked category → 57)',
    ]);
  });

  it('emits the selected card, merchant and the amount in minor units', async () => {
    const radio = el().querySelectorAll<HTMLInputElement>('input[type=radio]')[3];
    radio.click();
    const select = el().querySelector<HTMLSelectElement>('#t-merchant')!;
    select.value = select.options[2].value;
    select.dispatchEvent(new Event('change'));
    await setAmount('1200.5');
    await submit();
    expect(sent.length).toBe(1);
    expect(sent[0].card.id).toBe('low-balance');
    expect(sent[0].merchant.mcc).toBe('5812');
    expect(sent[0].amountMinor).toBe(120050);
  });

  it('rejects amounts that are not positive major units', async () => {
    for (const bad of ['0', '-5', '12.345', '1e3', 'abc']) {
      await setAmount(bad);
      await submit();
      expect(el().querySelector('#t-amount-err'), bad).not.toBeNull();
      expect(amount().getAttribute('aria-invalid')).toBe('true');
    }
    expect(sent).toEqual([]);
  });

  it('does not send while a request is in flight', async () => {
    fixture.componentRef.setInput('busy', true);
    await fixture.whenStable();
    expect(el().querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
    await submit();
    expect(sent).toEqual([]);
  });

  it('can be pre-filled by the stand-in suggestions', async () => {
    fixture.componentInstance.fill('debit', '4000.00');
    await fixture.whenStable();
    expect(amount().value).toBe('4000.00');
    await submit();
    expect(sent[0].card.id).toBe('debit');
    expect(sent[0].amountMinor).toBe(400000);
  });
});
