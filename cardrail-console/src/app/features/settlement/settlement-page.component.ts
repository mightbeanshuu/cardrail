import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subject, catchError, combineLatest, map, of, startWith, switchMap, tap } from 'rxjs';
import { ConfirmDialogComponent } from '../../shared/confirm-dialog.component';
import { isIsoDate, todayUtc } from '../../shared/datetime';
import { LoadState, asApiError } from '../../shared/load-state';
import { currencyInfo } from '../../shared/money';
import { Page, pageRange } from '../../shared/page';
import { MaskedPanPipe, MoneyPipe, UtcPipe } from '../../shared/pipes';
import { ChipComponent } from '../../shared/status-chip.component';
import { ClearingRecord, SettlementRun, reconcile, sortPositions } from './clearing.model';
import { ClearingService } from './clearing.service';

const RECORDS_PAGE_SIZE = 50;

@Component({
  selector: 'app-settlement-page',
  imports: [ReactiveFormsModule, RouterLink, MoneyPipe, UtcPipe, MaskedPanPipe, ChipComponent, ConfirmDialogComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './settlement-page.component.html',
  styles: `
    .run-summary { display: flex; flex-wrap: wrap; gap: 8px 24px; align-items: center; }
    .run-summary .muted { margin-right: 6px; }
    .date-form { display: flex; flex-wrap: wrap; gap: 8px; align-items: flex-end; }
    .run-failure { margin-top: 10px; color: var(--danger); }
  `,
})
export class SettlementPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly clearing = inject(ClearingService);
  private readonly reloadRun$ = new Subject<void>();

  protected readonly businessDate = toSignal(
    this.route.queryParamMap.pipe(map((p) => (isIsoDate(p.get('date')) ? (p.get('date') as string) : todayUtc()))),
    { requireSync: true },
  );
  protected readonly recordsPage = toSignal(
    this.route.queryParamMap.pipe(map((p) => Math.max(0, Number.parseInt(p.get('page') ?? '0', 10) || 0))),
    { requireSync: true },
  );

  protected readonly dateControl = new FormControl(this.businessDate(), {
    nonNullable: true,
    validators: [Validators.required],
  });
  protected readonly run = signal<LoadState<SettlementRun | null>>({ status: 'loading' });
  protected readonly records = signal<LoadState<Page<ClearingRecord>>>({ status: 'loading' });
  protected readonly running = signal(false);
  protected readonly downloading = signal(false);

  protected readonly currentRun = computed(() => {
    const r = this.run();
    return r.status === 'ok' ? r.data : null;
  });
  protected readonly positions = computed(() => sortPositions(this.currentRun()?.positions ?? []));
  protected readonly totals = computed(() => reconcile(this.positions()));
  protected readonly balanced = computed(() => this.totals().every((t) => t.balanced));
  protected readonly recordsRange = computed(() => {
    const r = this.records();
    return r.status === 'ok' ? pageRange(r.data) : null;
  });

  constructor() {
    combineLatest([this.route.queryParamMap, this.reloadRun$.pipe(startWith(undefined))])
      .pipe(
        map(() => this.businessDate()),
        tap((date) => {
          this.dateControl.setValue(date, { emitEvent: false });
          this.run.set({ status: 'loading' });
        }),
        switchMap((date) =>
          this.clearing.getRun(date).pipe(
            map((data): LoadState<SettlementRun | null> => ({ status: 'ok', data })),
            catchError((e: unknown) => of<LoadState<SettlementRun | null>>({ status: 'error', error: asApiError(e) })),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((s) => this.run.set(s));

    combineLatest([this.route.queryParamMap, this.reloadRun$.pipe(startWith(undefined))])
      .pipe(
        map(() => [this.businessDate(), this.recordsPage()] as const),
        tap(() => this.records.set({ status: 'loading' })),
        switchMap(([date, page]) =>
          this.clearing.records(date, page, RECORDS_PAGE_SIZE).pipe(
            map((data): LoadState<Page<ClearingRecord>> => ({ status: 'ok', data })),
            catchError((e: unknown) => of<LoadState<Page<ClearingRecord>>>({ status: 'error', error: asApiError(e) })),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((s) => this.records.set(s));
  }

  protected showDate(): void {
    const date = this.dateControl.value;
    if (!isIsoDate(date)) {
      this.dateControl.markAsTouched();
      return;
    }
    void this.router.navigate([], { relativeTo: this.route, queryParams: { date } });
  }

  protected goToRecordsPage(page: number): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { date: this.businessDate(), page: page > 0 ? page : null },
    });
  }

  protected runSettlement(): void {
    this.running.set(true);
    this.clearing.run(this.businessDate()).subscribe({
      next: () => {
        this.running.set(false);
        this.reloadRun$.next();
      },
      // A failed run is recorded by the service; reload so its status and failure reason show.
      error: () => {
        this.running.set(false);
        this.reloadRun$.next();
      },
    });
  }

  protected downloadCsv(): void {
    const date = this.businessDate();
    this.downloading.set(true);
    this.clearing.reportCsv(date).subscribe({
      next: (blob) => {
        this.downloading.set(false);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = this.clearing.reportFileName(date);
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 0);
      },
      error: () => this.downloading.set(false),
    });
  }

  protected currencyCode(numeric: number): string {
    return currencyInfo(numeric)?.alpha ?? String(numeric);
  }

  protected partyLabel(type: string): string {
    return type === 'ISSUER' ? 'Issuer BIN' : type === 'ACQUIRER' ? 'Acquirer' : type === 'NETWORK' ? 'Network' : type;
  }

  /** Safe for display: reconciliation totals are tiny integers or exactly zero in practice. */
  protected toNumber(v: bigint): number {
    return Number(v);
  }

  protected runLabel(status: string): string {
    return status.charAt(0) + status.slice(1).toLowerCase();
  }

  protected runTone(status: string): 'success' | 'danger' | 'info' | 'neutral' {
    const s = status.toUpperCase();
    if (s === 'COMPLETED') return 'success';
    if (s === 'FAILED') return 'danger';
    if (s === 'RUNNING') return 'info';
    return 'neutral';
  }
}
