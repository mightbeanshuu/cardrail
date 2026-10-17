import { HttpContext, HttpContextToken } from '@angular/common/http';

/**
 * Statuses the caller handles itself (for example 404 on a lookup), so the error interceptor still
 * maps them to an ApiError but does not raise the global banner.
 */
export const HANDLED_ERROR_STATUSES = new HttpContextToken<readonly number[]>(() => []);

export function handledStatuses(...statuses: number[]): HttpContext {
  return new HttpContext().set(HANDLED_ERROR_STATUSES, statuses);
}
