import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { defer, switchMap } from 'rxjs';
import { APP_CONFIG } from '../config/app-config';
import { SKIP_WARM_UP } from '../http/http-context';
import { ServiceId, WarmUpService } from './warm-up.service';

/** Which service a request goes to, by base URL. The clearing prefix is checked first: with the dev
 * proxy the switch's base URL is empty and would match everything. */
export function serviceFor(url: string, switchBaseUrl: string, clearingBaseUrl: string): ServiceId | null {
  if (clearingBaseUrl && url.startsWith(clearingBaseUrl)) return 'clearing';
  if (switchBaseUrl === '' ? url.startsWith('/') : url.startsWith(switchBaseUrl)) return 'switch';
  return null;
}

/** Holds a request until its service has woken up (or warm-up gave up), then sends it. */
export const warmUpInterceptor: HttpInterceptorFn = (req, next) => {
  const warmUp = inject(WarmUpService);
  if (!warmUp.enabled || req.context.get(SKIP_WARM_UP)) {
    return next(req);
  }
  const config = inject(APP_CONFIG);
  const service = serviceFor(req.url, config.switchBaseUrl, config.clearingBaseUrl);
  if (service == null) {
    return next(req);
  }
  return defer(() => warmUp.whenSettled(service)).pipe(switchMap(() => next(req)));
};
