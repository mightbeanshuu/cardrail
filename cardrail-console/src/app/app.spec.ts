import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TEST_CONFIG, provideTestHttp } from '../testing/test-providers';
import { App } from './app';
import { REPO_URL } from './demo/demo-links';

describe('App shell', () => {
  async function render(devTools: boolean, warmUp = false): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        ...provideTestHttp({ devTools, warmUp: { ...TEST_CONFIG.warmUp, enabled: warmUp } }),
        provideRouter([]),
      ],
    });
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const navLabels = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('nav[aria-label=Primary] a')).map((a) => a.textContent?.trim());

  it('shows the issuer simulator when devTools is on (the demo build)', async () => {
    expect(navLabels(await render(true))).toEqual([
      'Overview',
      'Terminal',
      'Authorizations',
      'Audit',
      'Settlement',
      'Issuer simulator',
    ]);
  });

  it('hides the issuer simulator when devTools is off', async () => {
    expect(navLabels(await render(false))).toEqual(['Overview', 'Terminal', 'Authorizations', 'Audit', 'Settlement']);
  });

  it('has a skip link to the main region', async () => {
    const el = await render(false);
    expect(el.querySelector('a.skip-link')?.getAttribute('href')).toBe('#main');
    expect(el.querySelector('main#main')).not.toBeNull();
  });

  it('links to the repository in the footer', async () => {
    const el = await render(false);
    const links = Array.from(el.querySelectorAll('footer a')).map((a) => a.getAttribute('href'));
    expect(links).toContain(REPO_URL);
  });

  it('starts waking both services as soon as the app loads', async () => {
    await render(true, true);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(TEST_CONFIG.warmUp.switchHealthUrl).flush({ status: 'UP' });
    http.expectOne(TEST_CONFIG.warmUp.clearingHealthUrl).flush({ status: 'UP' });
    http.verify();
  });
});
