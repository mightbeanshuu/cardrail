import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subject, catchError, combineLatest, map, of, startWith, switchMap, tap } from 'rxjs';
import { APP_CONFIG } from '../../core/config/app-config';
import { ConfirmDialogComponent } from '../../shared/confirm-dialog.component';
import { LoadState, asApiError } from '../../shared/load-state';
import { formatMinor } from '../../shared/money';
import { MaskedPanPipe, MoneyPipe, UtcPipe } from '../../shared/pipes';
import { ResponseCodeComponent, StipBadgeComponent } from '../../shared/response-code.component';
import { StatusChipComponent } from '../../shared/status-chip.component';
import { authorizationTimeline } from './authorization-timeline';
import { AuthorizationView } from './authorization.model';
import { AuthorizationsService } from './authorizations.service';
import { CaptureFormComponent } from './capture-form.component';

interface Outcome {
  readonly tone: 'success' | 'info';
  readonly text: string;
}

@Component({
  selector: 'app-authorization-detail',
  imports: [
    RouterLink,
    MoneyPipe,
    UtcPipe,
    MaskedPanPipe,
    StatusChipComponent,
    ResponseCodeComponent,
    StipBadgeComponent,
    ConfirmDialogComponent,
    CaptureFormComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './authorization-detail.component.html',
  styleUrl: './authorization-detail.component.css',
})
export class AuthorizationDetailComponent {
  private readonly service = inject(AuthorizationsService);
  private readonly locale = inject(APP_CONFIG).locale;
  private readonly reload$ = new Subject<void>();

  /** Bound from the `:id` route parameter. */
  readonly id = input.required<string>();

  protected readonly state = signal<LoadState<AuthorizationView>>({ status: 'loading' });
  protected readonly busy = signal<'reverse' | 'capture' | null>(null);
  protected readonly outcome = signal<Outcome | null>(null);

  private readonly confirmReverse = viewChild.required<ConfirmDialogComponent>('confirmReverse');
  private readonly captureForm = viewChild(CaptureFormComponent);
  private readonly outcomeEl = viewChild<ElementRef<HTMLElement>>('outcomeEl');

  protected readonly auth = computed(() => {
    const s = this.state();
    return s.status === 'ok' ? s.data : null;
  });
  protected readonly loadError = computed(() => {
    const s = this.state();
    return s.status === 'error' ? s.error : null;
  });
  protected readonly timeline = computed(() => {
    const a = this.auth();
    return a ? authorizationTimeline(a, this.locale) : [];
  });
  /** Reverse and capture both need an open hold. */
  protected readonly actionBlockedReason = computed(() => {
    const a = this.auth();
    if (!a) return 'Not loaded.';
    if (this.busy()) return 'Another action is in progress.';
    return a.status === 'AUTHORIZED'
      ? null
      : `Only authorizations in status Authorized can be reversed or captured (this one is ${a.status.toLowerCase()}).`;
  });

  constructor() {
    combineLatest([toObservable(this.id), this.reload$.pipe(startWith(undefined))])
      .pipe(
        tap(() => {
          if (this.state().status !== 'ok') this.state.set({ status: 'loading' });
        }),
        switchMap(([id]) =>
          this.service.get(id).pipe(
            map((data): LoadState<AuthorizationView> => ({ status: 'ok', data })),
            catchError((e: unknown) => of<LoadState<AuthorizationView>>({ status: 'error', error: asApiError(e) })),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((s) => this.state.set(s));
  }

  protected askReverse(trigger: HTMLElement): void {
    this.outcome.set(null);
    this.confirmReverse().open(trigger);
  }

  protected reverse(): void {
    const a = this.auth();
    if (!a || this.actionBlockedReason()) return;
    this.busy.set('reverse');
    this.service.reverse(a.id).subscribe({
      next: (r) => {
        this.busy.set(null);
        this.announce(
          r.outcome === 'REVERSED'
            ? { tone: 'success', text: `Reversed. ${formatMinor(r.releasedMinor, a.currency, this.locale)} released.` }
            : { tone: 'info', text: 'Nothing to reverse: the authorization was declined or its hold had already expired.' },
        );
        this.reload$.next();
      },
      error: () => this.busy.set(null),
    });
  }

  protected capture(amountMinor: number | null): void {
    const a = this.auth();
    if (!a || this.actionBlockedReason()) return;
    this.outcome.set(null);
    this.busy.set('capture');
    this.service.capture(a.id, amountMinor).subscribe({
      next: (r) => {
        this.busy.set(null);
        this.captureForm()?.reset();
        const captured = formatMinor(r.capturedMinor, a.currency, this.locale);
        this.announce({
          tone: 'success',
          text:
            r.releasedMinor > 0
              ? `Captured ${captured}. ${formatMinor(r.releasedMinor, a.currency, this.locale)} released.`
              : `Captured ${captured} in full.`,
        });
        this.reload$.next();
      },
      error: () => this.busy.set(null),
    });
  }

  protected retry(): void {
    this.state.set({ status: 'loading' });
    this.reload$.next();
  }

  /** Shows the result and moves focus to it, since the action buttons become disabled. */
  private announce(outcome: Outcome): void {
    this.outcome.set(outcome);
    setTimeout(() => this.outcomeEl()?.nativeElement.focus());
  }
}
