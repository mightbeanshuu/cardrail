import { AppConfig } from '../app/core/config/app-config';

/**
 * Default configuration: same-origin paths, for serving the console behind the same reverse proxy
 * as the APIs. `ng build` (production) replaces this file with environment.prod.ts and `ng serve`
 * with environment.development.ts.
 */
export const environment: AppConfig = {
  switchBaseUrl: '',
  clearingBaseUrl: '/clearing',
  warmUp: { enabled: false, switchHealthUrl: '/actuator/health', clearingHealthUrl: '/clearing-actuator/health' },
  devTools: false,
  locale: 'en-IN',
};
