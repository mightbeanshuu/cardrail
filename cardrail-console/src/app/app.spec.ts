import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTestHttp } from '../testing/test-providers';
import { App } from './app';

describe('App shell', () => {
  async function render(devTools: boolean): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [...provideTestHttp({ devTools }), provideRouter([])],
    });
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const navLabels = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('nav[aria-label=Primary] a')).map((a) => a.textContent?.trim());

  it('shows the issuer simulator only when devTools is on', async () => {
    expect(navLabels(await render(true))).toEqual(['Authorizations', 'Audit', 'Settlement', 'Issuer simulator']);
  });

  it('hides the issuer simulator when devTools is off', async () => {
    expect(navLabels(await render(false))).toEqual(['Authorizations', 'Audit', 'Settlement']);
  });

  it('has a skip link to the main region', async () => {
    const el = await render(false);
    expect(el.querySelector('a.skip-link')?.getAttribute('href')).toBe('#main');
    expect(el.querySelector('main#main')).not.toBeNull();
  });
});
