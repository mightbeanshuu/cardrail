import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { APP_CONFIG } from '../../core/config/app-config';

/** Mirrors `AuditController.VerificationBody`; the broken-chain fields are omitted when intact. */
export interface ChainVerification {
  readonly intact: boolean;
  readonly recordsVerified: number;
  readonly firstBrokenSeq?: number;
  readonly reason?: string;
}

@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(APP_CONFIG).switchBaseUrl}/v1/audit`;

  /** Recomputes the whole hash chain on the switch; can take a while on a large log. */
  verify(): Observable<ChainVerification> {
    return this.http.get<ChainVerification>(`${this.base}/verify`);
  }
}
