import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, of, throwError } from 'rxjs';
import { APP_CONFIG } from '../../core/config/app-config';
import { ApiError } from '../../core/errors/api-error';
import { handledStatuses } from '../../core/http/http-context';
import { Page } from '../../shared/page';
import { ClearingRecord, SettlementRun } from './clearing.model';

/** cardrail-clearing (docs/CONTRACTS.md section 3). The base URL maps to the service's `/v1`. */
@Injectable({ providedIn: 'root' })
export class ClearingService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(APP_CONFIG).clearingBaseUrl;

  records(businessDate: string, page: number, size: number): Observable<Page<ClearingRecord>> {
    const params = new HttpParams().set('date', businessDate).set('page', page).set('size', size);
    return this.http.get<Page<ClearingRecord>>(`${this.base}/clearing-records`, { params });
  }

  /** Runs end-of-day settlement for the date. The service makes this idempotent per date. */
  run(businessDate: string): Observable<SettlementRun> {
    return this.http.post<SettlementRun>(`${this.base}/settlement-runs`, { businessDate });
  }

  /** Emits null when no run exists for the date yet (404). */
  getRun(businessDate: string): Observable<SettlementRun | null> {
    return this.http
      .get<SettlementRun>(`${this.base}/settlement-runs/${encodeURIComponent(businessDate)}`, {
        context: handledStatuses(404),
      })
      .pipe(catchError((e: unknown) => (e instanceof ApiError && e.isNotFound ? of(null) : throwError(() => e))));
  }

  reportCsv(businessDate: string): Observable<Blob> {
    return this.http.get(`${this.base}/settlement-runs/${encodeURIComponent(businessDate)}/report.csv`, {
      responseType: 'blob',
      headers: { Accept: 'text/csv' },
    });
  }

  reportFileName(businessDate: string): string {
    return `settlement-${businessDate}.csv`;
  }
}
