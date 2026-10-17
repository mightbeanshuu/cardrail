import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { routes } from './app.routes';
import { correlationIdInterceptor } from './core/http/correlation-id.interceptor';
import { problemInterceptor } from './core/http/problem.interceptor';
import { warmUpInterceptor } from './core/warm-up/warm-up.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ anchorScrolling: 'enabled', scrollPositionRestoration: 'enabled' }),
    ),
    // Order matters: requests wait for a sleeping backend first, then get a correlation id, which
    // the error interceptor reads.
    provideHttpClient(
      withFetch(),
      withInterceptors([warmUpInterceptor, correlationIdInterceptor, problemInterceptor]),
    ),
  ],
};
