import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { APP_CONFIG } from '../../core/config/app-config';
import { QUIET_ERRORS } from '../../core/http/http-context';

/** Mirrors `SimulatedIssuerAdminController.FaultSettings`. */
export interface FaultSettings {
  readonly latencyMs: number;
  /** 0.0 to 1.0. */
  readonly failureRate: number;
}

/**
 * The switch's simulated issuer (`/dev/issuer/faults`: GET, PUT, DELETE). Exposed by the switch's
 * `dev` and `demo` profiles only.
 */
@Injectable({ providedIn: 'root' })
export class IssuerFaultsService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).switchBaseUrl}/dev/issuer/faults`;

  /** `quiet`: the caller shows its own unavailable state, so failures do not raise the error banner. */
  current(options: { quiet?: boolean } = {}): Observable<FaultSettings> {
    return this.http.get<FaultSettings>(this.url, options.quiet ? { context: new HttpContext().set(QUIET_ERRORS, true) } : {});
  }

  set(settings: FaultSettings): Observable<FaultSettings> {
    return this.http.put<FaultSettings>(this.url, settings);
  }

  /** Restores the settings the switch was started with. */
  reset(): Observable<FaultSettings> {
    return this.http.delete<FaultSettings>(this.url);
  }
}
