import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  input,
  output,
  viewChild,
} from '@angular/core';

let nextId = 0;

/**
 * Modal confirmation built on the native <dialog> element (focus containment, Escape to cancel).
 * Focus starts on Cancel, and returns to the element that opened the dialog when it closes.
 */
@Component({
  selector: 'app-confirm-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog #dialog class="dialog" [attr.aria-labelledby]="headingId" [attr.aria-describedby]="bodyId"
            (close)="onNativeClose()">
      <h2 class="dialog__title" [id]="headingId">{{ heading() }}</h2>
      <div class="dialog__body" [id]="bodyId"><ng-content /></div>
      <div class="dialog__actions">
        <button #cancelButton type="button" class="btn" (click)="close(false)">Cancel</button>
        <button type="button" [class]="'btn ' + (danger() ? 'btn--danger' : 'btn--primary')" (click)="close(true)">
          {{ confirmLabel() }}
        </button>
      </div>
    </dialog>
  `,
})
export class ConfirmDialogComponent {
  readonly heading = input.required<string>();
  readonly confirmLabel = input('Confirm');
  readonly danger = input(false);
  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  protected readonly headingId = `confirm-title-${++nextId}`;
  protected readonly bodyId = `confirm-body-${nextId}`;

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private readonly cancelButton = viewChild.required<ElementRef<HTMLButtonElement>>('cancelButton');
  private returnFocusTo: HTMLElement | null = null;
  private result = false;
  private isOpen = false;

  open(returnFocusTo?: HTMLElement | null): void {
    const el = this.dialog().nativeElement;
    this.returnFocusTo = returnFocusTo ?? (document.activeElement as HTMLElement | null);
    this.result = false;
    this.isOpen = true;
    if (typeof el.showModal === 'function') {
      el.showModal();
    } else {
      el.setAttribute('open', '');
    }
    this.cancelButton().nativeElement.focus();
  }

  close(confirmed: boolean): void {
    this.result = confirmed;
    const el = this.dialog().nativeElement;
    if (el.open && typeof el.close === 'function') {
      el.close();
    } else {
      el.removeAttribute('open');
    }
    // Browsers fire 'close' asynchronously; finish now so callers see a consistent state. The later
    // native event is then a no-op.
    this.finish();
  }

  /** Fired by the browser after close(), including when Escape dismisses the dialog. */
  protected onNativeClose(): void {
    this.finish();
  }

  private finish(): void {
    if (!this.isOpen) {
      return;
    }
    this.isOpen = false;
    if (this.result) {
      this.confirmed.emit();
    } else {
      this.cancelled.emit();
    }
    this.returnFocusTo?.focus();
    this.returnFocusTo = null;
  }
}
