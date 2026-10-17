import { InjectionToken } from '@angular/core';
import { environment } from '../../../environments/environment';

export interface AppConfig {
  /** Origin of cardrail-switch. Paths such as `/v1/authorizations` are appended. Empty = same origin. */
  switchBaseUrl: string;
  /**
   * Prefix that maps to cardrail-clearing's `/v1`. With the dev proxy this is `/clearing`; without it,
   * use `http://localhost:8081/v1`.
   */
  clearingBaseUrl: string;
  /**
   * Health endpoints pinged when the app loads, so instances that sleep when idle (the public demo
   * runs on a free tier) start waking before the visitor's first real request. Requests to a service
   * wait until its health check has answered or given up.
   */
  warmUp: {
    readonly enabled: boolean;
    readonly switchHealthUrl: string;
    readonly clearingHealthUrl: string;
  };
  /** Shows the issuer simulator (page, nav entry and the stand-in controls on the terminal). */
  devTools: boolean;
  /** Locale for number and date formatting. */
  locale: string;
}

export const APP_CONFIG = new InjectionToken<AppConfig>('APP_CONFIG', {
  providedIn: 'root',
  factory: () => environment,
});
