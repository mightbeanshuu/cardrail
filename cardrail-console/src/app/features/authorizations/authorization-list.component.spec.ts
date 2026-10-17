import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { authorization } from '../../../testing/fixtures';
import { provideTestHttp } from '../../../testing/test-providers';
import { AuthorizationView } from './authorization.model';
import { AuthorizationListComponent } from './authorization-list.component';

describe('AuthorizationListComponent', () => {
  const base = 'http://switch.test/v1/authorizations';
  let harness: RouterTestingHarness;
  let controller: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        ...provideTestHttp(),
        provideRouter([
          { path: 'authorizations', component: AuthorizationListComponent },
          { path: 'authorizations/:id', children: [] },
        ]),
      ],
    });
    controller = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => controller.verify());

  const el = () => harness.routeNativeElement as HTMLElement;
  const bodyRows = () => Array.from(el().querySelectorAll('tbody tr'));

  function pendingSearch(): TestRequest {
    return controller.expectOne((r) => r.url === base);
  }

  async function open(url: string): Promise<void> {
    await harness.navigateByUrl(url, AuthorizationListComponent);
  }

  async function respond(req: TestRequest, items: AuthorizationView[], total = items.length, page = 0, size = 20) {
    req.flush({ items, page, size, total });
    await harness.fixture.whenStable();
  }

  it('shows skeleton rows while loading', async () => {
    await open('/authorizations');
    expect(el().querySelector('table')?.getAttribute('aria-busy')).toBe('true');
    expect(el().querySelectorAll('tbody tr.skeleton-row').length).toBe(8);
    await respond(pendingSearch(), []);
  });

  it('renders one row per authorization with formatted fields', async () => {
    await open('/authorizations');
    await respond(pendingSearch(), [
      authorization(),
      authorization({
        id: '0f1e2d3c-0000-4000-8000-000000000001',
        status: 'DECLINED',
        responseCode: '51',
        standIn: true,
        amountMinor: 123456,
        currency: 840,
        authCode: null,
      }),
    ]);

    const rows = bodyRows();
    expect(rows.length).toBe(2);
    const first = rows[0].textContent!.replace(/\s+/g, ' ');
    expect(first).toContain('2026-10-18 09:15:00');
    expect(first).toContain('555555******4444');
    expect(first).toContain('₹250.00');
    expect(first).toContain('5411');
    expect(first).toContain('MERCHANT000001');
    expect(first).toContain('00 Approved');
    expect(first).toContain('Authorized');
    expect(rows[0].querySelector('.badge--stip')).toBeNull();

    const second = rows[1].textContent!.replace(/\s+/g, ' ');
    expect(second).toContain('$1,234.56');
    expect(second).toContain('51 Insufficient funds');
    expect(second).toContain('Declined');
    expect(rows[1].querySelector('.badge--stip')?.textContent).toBe('STIP');

    const link = rows[0].querySelector('a')!;
    expect(link.getAttribute('href')).toBe('/authorizations/7b0d3c2e-3f7a-4d6e-9a1b-2c4d5e6f7a8b');
    expect(el().querySelector('table')?.getAttribute('aria-busy')).toBe('false');
    expect(el().querySelector('th[aria-sort]')?.getAttribute('aria-sort')).toBe('descending');
  });

  it('shows an empty state', async () => {
    await open('/authorizations');
    await respond(pendingSearch(), []);
    expect(bodyRows().length).toBe(1);
    expect(el().textContent).toContain('No authorizations yet.');
    expect(el().querySelector('nav[aria-label=Pagination]')).toBeNull();
  });

  it('offers to clear filters when filters match nothing', async () => {
    await open('/authorizations?status=DECLINED');
    const req = pendingSearch();
    expect(req.request.params.get('status')).toBe('DECLINED');
    await respond(req, []);
    expect(el().textContent).toContain('No authorizations match these filters.');
    expect(el().querySelector<HTMLSelectElement>('#f-status')!.value).toBe('DECLINED');
  });

  it('shows an error state with retry', async () => {
    await open('/authorizations');
    pendingSearch().flush({ title: 'Internal error', status: 500 }, { status: 500, statusText: 'x' });
    await harness.fixture.whenStable();
    expect(el().textContent).toContain('Authorizations could not be loaded.');
    const retry = Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Retry')!;
    retry.click();
    await harness.fixture.whenStable();
    await respond(pendingSearch(), [authorization()]);
    expect(bodyRows().length).toBe(1);
  });

  it('pages through results via the URL', async () => {
    await open('/authorizations');
    await respond(pendingSearch(), Array.from({ length: 20 }, (_, i) => authorization({ id: `id-${i}` })), 45);

    const pager = el().querySelector('nav[aria-label=Pagination]')!;
    expect(pager.textContent).toContain('Page 1 of 3');
    const [prev, next] = Array.from(pager.querySelectorAll('button'));
    expect(prev.disabled).toBe(true);
    expect(next.disabled).toBe(false);

    next.click();
    await harness.fixture.whenStable();
    expect(router.url).toBe('/authorizations?page=1');
    const req = pendingSearch();
    expect(req.request.params.get('page')).toBe('1');
    await respond(req, Array.from({ length: 20 }, (_, i) => authorization({ id: `id-${20 + i}` })), 45, 1);
    expect(el().querySelector('nav[aria-label=Pagination]')!.textContent).toContain('Page 2 of 3');
  });

  it('applies filters to the URL and resets to the first page', async () => {
    await open('/authorizations?page=2');
    await respond(pendingSearch(), [], 0, 2);

    const status = el().querySelector<HTMLSelectElement>('#f-status')!;
    status.value = 'CAPTURED';
    status.dispatchEvent(new Event('change'));
    const last4 = el().querySelector<HTMLInputElement>('#f-last4')!;
    last4.value = '4444';
    last4.dispatchEvent(new Event('input'));
    const stip = el().querySelector<HTMLSelectElement>('#f-stip')!;
    stip.value = 'true';
    stip.dispatchEvent(new Event('change'));
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await harness.fixture.whenStable();

    expect(router.url).toBe('/authorizations?status=CAPTURED&last4=4444&standIn=true');
    const req = pendingSearch();
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('last4')).toBe('4444');
    expect(req.request.params.get('standIn')).toBe('true');
    await respond(req, []);
  });

  it('refuses a full card number in the merchant filter', async () => {
    await open('/authorizations');
    await respond(pendingSearch(), []);
    const merchant = el().querySelector<HTMLInputElement>('#f-merchant')!;
    merchant.value = '5555555555554444';
    merchant.dispatchEvent(new Event('input'));
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await harness.fixture.whenStable();
    expect(router.url).toBe('/authorizations');
    expect(el().querySelector('#f-merchant-err')?.textContent).toContain('looks like a card number');
    controller.expectNone((r) => r.url === base);
  });
});
