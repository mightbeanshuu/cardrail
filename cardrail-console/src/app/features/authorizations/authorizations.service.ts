import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { APP_CONFIG } from '../../core/config/app-config';
import { handledStatuses } from '../../core/http/http-context';
import { Page } from '../../shared/page';
import { AuthorizationQuery, queryToHttpParams } from './authorization-query';
import { AuthorizationView, CaptureResult, ReversalResult } from './authorization.model';

/** cardrail-switch `/v1/authorizations`. Errors arrive as ApiError via the problem interceptor. */
@Injectable({ providedIn: 'root' })
export class AuthorizationsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(APP_CONFIG).switchBaseUrl}/v1/authorizations`;

  search(query: AuthorizationQuery): Observable<Page<AuthorizationView>> {
    return this.http.get<Page<AuthorizationView>>(this.base, { params: queryToHttpParams(query) });
  }

  /** 404 is reported to the caller only (the detail page shows its own not-found state). */
  get(id: string): Observable<AuthorizationView> {
    return this.http.get<AuthorizationView>(`${this.base}/${encodeURIComponent(id)}`, {
      context: handledStatuses(404),
    });
  }

  reverse(id: string): Observable<ReversalResult> {
    return this.http.post<ReversalResult>(`${this.base}/${encodeURIComponent(id)}/reversal`, null);
  }

  /** `amountMinor` null captures the full authorized amount. */
  capture(id: string, amountMinor: number | null): Observable<CaptureResult> {
    const body = amountMinor == null ? {} : { amountMinor };
    return this.http.post<CaptureResult>(`${this.base}/${encodeURIComponent(id)}/capture`, body);
  }
}
