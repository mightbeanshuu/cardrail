import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { APP_CONFIG } from '../../core/config/app-config';

/** Mirrors `SimulatedIssuerAdminController.FaultSettings`. */
export interface FaultSettings {
  readonly latencyMs: number;
  /** 0.0 to 1.0. */
  readonly failureRate: number;
}

/** Dev profile only: the switch's simulated issuer (`/dev/issuer/faults`: GET, PUT, DELETE). */
@Injectable({ providedIn: 'root' })
export class IssuerFaultsService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).switchBaseUrl}/dev/issuer/faults`;

  current(): Observable<FaultSettings> {
    return this.http.get<FaultSettings>(this.url);
  }

  set(settings: FaultSettings): Observable<FaultSettings> {
    return this.http.put<FaultSettings>(this.url, settings);
  }

  /** Restores the settings the switch was started with. */
  reset(): Observable<FaultSettings> {
    return this.http.delete<FaultSettings>(this.url);
  }
}
