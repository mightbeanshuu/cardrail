import { HttpContext, HttpContextToken } from '@angular/common/http';

/**
 * Statuses the caller handles itself (for example 404 on a lookup), so the error interceptor still
 * maps them to an ApiError but does not raise the global banner.
 */
export const HANDLED_ERROR_STATUSES = new HttpContextToken<readonly number[]>(() => []);

/** The caller handles every failure itself (health checks, optional probes): never raise the banner. */
export const QUIET_ERRORS = new HttpContextToken<boolean>(() => false);

/** Set on the warm-up health checks themselves, so the warm-up interceptor does not hold them back. */
export const SKIP_WARM_UP = new HttpContextToken<boolean>(() => false);

export function handledStatuses(...statuses: number[]): HttpContext {
  return new HttpContext().set(HANDLED_ERROR_STATUSES, statuses);
}
