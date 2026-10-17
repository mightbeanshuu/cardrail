import { AppConfig } from '../app/core/config/app-config';

/**
 * Production build, deployed as the public demo at https://cardrail-demo.vercel.app. The services
 * run on Render's free tier and allow this origin via CORS (`cardrail.web.allowed-origins` for the
 * APIs, `management.endpoints.web.cors.*` for the health endpoints).
 */
const SWITCH = 'https://cardrail-switch.onrender.com';
const CLEARING = 'https://cardrail-clearing.onrender.com';

export const environment: AppConfig = {
  switchBaseUrl: SWITCH,
  clearingBaseUrl: `${CLEARING}/v1`,
  warmUp: {
    enabled: true,
    switchHealthUrl: `${SWITCH}/actuator/health`,
    clearingHealthUrl: `${CLEARING}/actuator/health`,
  },
  devTools: true,
  locale: 'en-IN',
};
