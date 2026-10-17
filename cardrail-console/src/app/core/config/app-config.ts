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
  /** Shows the issuer simulator page. The switch only exposes it under the `dev` profile. */
  devTools: boolean;
  /** Locale for number and date formatting. */
  locale: string;
}

export const APP_CONFIG = new InjectionToken<AppConfig>('APP_CONFIG', {
  providedIn: 'root',
  factory: () => environment,
});
