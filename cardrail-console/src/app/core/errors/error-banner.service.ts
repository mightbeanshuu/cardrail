import { Injectable, signal } from '@angular/core';
import { ApiError } from './api-error';

export interface BannerEntry {
  readonly id: number;
  readonly error: ApiError;
  readonly count: number;
  readonly at: Date;
}

const MAX_ENTRIES = 3;

/** Holds the errors shown in the global banner. Repeats of the same error are folded into one entry. */
@Injectable({ providedIn: 'root' })
export class ErrorBannerService {
  private nextId = 1;
  private readonly _entries = signal<readonly BannerEntry[]>([]);
  readonly entries = this._entries.asReadonly();

  report(error: ApiError): void {
    this._entries.update((entries) => {
      const existing = entries.find(
        (e) => e.error.status === error.status && e.error.title === error.title && e.error.detail === error.detail,
      );
      if (existing) {
        return entries.map((e) =>
          e === existing ? { ...e, error, count: e.count + 1, at: new Date() } : e,
        );
      }
      const entry: BannerEntry = { id: this.nextId++, error, count: 1, at: new Date() };
      return [entry, ...entries].slice(0, MAX_ENTRIES);
    });
  }

  dismiss(id: number): void {
    this._entries.update((entries) => entries.filter((e) => e.id !== id));
  }

  clear(): void {
    this._entries.set([]);
  }
}
