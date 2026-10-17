import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subject, catchError, combineLatest, map, of, startWith, switchMap, tap } from 'rxjs';
import { LoadState, asApiError } from '../../shared/load-state';
import { Page, pageRange } from '../../shared/page';
import { looksLikePan } from '../../shared/pan';
import { MaskedPanPipe, MoneyPipe, UtcPipe } from '../../shared/pipes';
import { RESPONSE_CODE_OPTIONS } from '../../shared/response-codes';
import { ResponseCodeComponent, StipBadgeComponent } from '../../shared/response-code.component';
import { StatusChipComponent } from '../../shared/status-chip.component';
import {
  AuthorizationFilter,
  AuthorizationQuery,
  MERCHANT_ID_PATTERN,
  PAGE_SIZES,
  hasActiveFilter,
  queryFromParams,
  queryToParams,
} from './authorization-query';
import { AUTHORIZATION_STATUSES, AuthorizationStatus, AuthorizationView } from './authorization.model';
import { AuthorizationsService } from './authorizations.service';

function merchantIdValidator(control: AbstractControl<string>): ValidationErrors | null {
  const v = control.value?.trim();
  if (!v) return null;
  if (looksLikePan(v)) return { pan: true };
  return MERCHANT_ID_PATTERN.test(v) ? null : { pattern: true };
}

function dateRangeValidator(group: AbstractControl): ValidationErrors | null {
  const from = group.get('from')?.value as string;
  const to = group.get('to')?.value as string;
  return from && to && from > to ? { range: true } : null;
}

const SKELETON_ROWS = Array.from({ length: 8 }, (_, i) => i);

@Component({
  selector: 'app-authorization-list',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MoneyPipe,
    UtcPipe,
    MaskedPanPipe,
    StatusChipComponent,
    ResponseCodeComponent,
    StipBadgeComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './authorization-list.component.html',
  styleUrl: './authorization-list.component.css',
})
export class AuthorizationListComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly service = inject(AuthorizationsService);
  private readonly reload$ = new Subject<void>();

  protected readonly statuses = AUTHORIZATION_STATUSES;
  protected readonly responseCodes = RESPONSE_CODE_OPTIONS;
  protected readonly pageSizes = PAGE_SIZES;
  protected readonly skeletonRows = SKELETON_ROWS;

  protected readonly query = toSignal(this.route.queryParamMap.pipe(map(queryFromParams)), {
    requireSync: true,
  });
  protected readonly state = signal<LoadState<Page<AuthorizationView>>>({ status: 'loading' });

  protected readonly form = new FormGroup(
    {
      status: new FormControl('', { nonNullable: true }),
      responseCode: new FormControl('', { nonNullable: true }),
      merchantId: new FormControl('', { nonNullable: true, validators: [merchantIdValidator] }),
      last4: new FormControl('', { nonNullable: true, validators: [Validators.pattern(/^\d{4}$/)] }),
      standIn: new FormControl('', { nonNullable: true }),
      from: new FormControl('', { nonNullable: true }),
      to: new FormControl('', { nonNullable: true }),
    },
    { validators: [dateRangeValidator] },
  );

  protected readonly filtersActive = computed(() => hasActiveFilter(this.query().filter));
  protected readonly range = computed(() => {
    const s = this.state();
    return s.status === 'ok' ? pageRange(s.data) : null;
  });
  protected readonly announcement = computed(() => {
    const s = this.state();
    if (s.status === 'loading') return 'Loading authorizations';
    if (s.status === 'error') return 'Authorizations could not be loaded';
    if (s.status !== 'ok') return '';
    const r = pageRange(s.data);
    return r.total === 0 ? 'No authorizations match' : `Showing ${r.first} to ${r.last} of ${r.total} authorizations`;
  });

  constructor() {
    combineLatest([this.route.queryParamMap.pipe(map(queryFromParams)), this.reload$.pipe(startWith(undefined))])
      .pipe(
        tap(([q]) => {
          this.form.reset(this.toFormValue(q.filter), { emitEvent: false });
          this.state.set({ status: 'loading' });
        }),
        switchMap(([q]) =>
          this.service.search(q).pipe(
            map((data): LoadState<Page<AuthorizationView>> => ({ status: 'ok', data })),
            catchError((e: unknown) => of<LoadState<Page<AuthorizationView>>>({ status: 'error', error: asApiError(e) })),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((s) => this.state.set(s));
  }

  protected apply(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.navigate({ ...this.query(), filter: this.fromFormValue(), page: 0 });
  }

  protected clearFilters(): void {
    this.navigate({ ...this.query(), filter: {}, page: 0 });
  }

  protected goToPage(page: number): void {
    this.navigate({ ...this.query(), page });
  }

  protected setPageSize(value: string): void {
    this.navigate({ ...this.query(), size: Number(value), page: 0 });
  }

  protected retry(): void {
    this.reload$.next();
  }

  protected invalid(name: 'merchantId' | 'last4'): boolean {
    const c = this.form.controls[name];
    return c.invalid && (c.touched || c.dirty);
  }

  private navigate(q: AuthorizationQuery): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: queryToParams(q) });
  }

  private toFormValue(f: AuthorizationFilter) {
    return {
      status: f.status ?? '',
      responseCode: f.responseCode ?? '',
      merchantId: f.merchantId ?? '',
      last4: f.last4 ?? '',
      standIn: f.standIn === undefined ? '' : String(f.standIn),
      from: f.from ?? '',
      to: f.to ?? '',
    };
  }

  private fromFormValue(): AuthorizationFilter {
    const v = this.form.getRawValue();
    const f: { -readonly [K in keyof AuthorizationFilter]: AuthorizationFilter[K] } = {};
    if (v.status) f.status = v.status as AuthorizationStatus;
    if (v.responseCode) f.responseCode = v.responseCode;
    if (v.merchantId.trim()) f.merchantId = v.merchantId.trim();
    if (v.last4) f.last4 = v.last4;
    if (v.standIn) f.standIn = v.standIn === 'true';
    if (v.from) f.from = v.from;
    if (v.to) f.to = v.to;
    return f;
  }
}
