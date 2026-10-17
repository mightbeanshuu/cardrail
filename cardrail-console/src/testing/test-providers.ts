import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { APP_CONFIG, AppConfig } from '../app/core/config/app-config';
import { correlationIdInterceptor } from '../app/core/http/correlation-id.interceptor';
import { problemInterceptor } from '../app/core/http/problem.interceptor';

export const TEST_CONFIG: AppConfig = {
  switchBaseUrl: 'http://switch.test',
  clearingBaseUrl: 'http://clearing.test/v1',
  warmUp: {
    enabled: false,
    switchHealthUrl: 'http://switch.test/actuator/health',
    clearingHealthUrl: 'http://clearing.test/actuator/health',
  },
  devTools: true,
  locale: 'en-IN',
};

/** HttpClient with the real interceptors in front of HttpTestingController. */
export function provideTestHttp(config: Partial<AppConfig> = {}): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(withInterceptors([correlationIdInterceptor, problemInterceptor])),
    provideHttpClientTesting(),
    { provide: APP_CONFIG, useValue: { ...TEST_CONFIG, ...config } },
  ];
}

export const PROBLEM_HEADERS = { 'Content-Type': 'application/problem+json' };
