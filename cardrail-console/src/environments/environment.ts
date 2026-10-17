import { AppConfig } from '../app/core/config/app-config';

/**
 * Production build. Base URLs are relative so the console can be served behind the same reverse
 * proxy as the APIs; point them at absolute origins if the services allow this origin via CORS.
 */
export const environment: AppConfig = {
  switchBaseUrl: '',
  clearingBaseUrl: '/clearing',
  devTools: false,
  locale: 'en-IN',
};
