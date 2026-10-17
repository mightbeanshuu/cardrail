import { AppConfig } from '../app/core/config/app-config';

/** `ng serve`: requests go through proxy.conf.json to the local switch (8080) and clearing (8081). */
export const environment: AppConfig = {
  switchBaseUrl: '',
  clearingBaseUrl: '/clearing',
  warmUp: { enabled: true, switchHealthUrl: '/actuator/health', clearingHealthUrl: '/clearing-actuator/health' },
  devTools: true,
  locale: 'en-IN',
};
