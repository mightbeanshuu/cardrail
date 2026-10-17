import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, catchError, from, of, switchMap, throwError } from 'rxjs';
import { toApiError } from '../errors/api-error';
import { ErrorBannerService } from '../errors/error-banner.service';
import { CORRELATION_ID_HEADER } from './correlation-id.interceptor';
import { HANDLED_ERROR_STATUSES, QUIET_ERRORS } from './http-context';

/**
 * Turns every failed response into a typed ApiError (RFC 7807 fields where present) and reports it
 * to the non-blocking error banner, unless the caller declared that status as handled.
 */
export const problemInterceptor: HttpInterceptorFn = (req, next) => {
  const banner = inject(ErrorBannerService);
  return next(req).pipe(
    catchError((err: unknown) => {
      if (!(err instanceof HttpErrorResponse)) {
        return throwError(() => err);
      }
      return readErrorBody(err.error).pipe(
        switchMap((body) => {
          const apiError = toApiError(err, body, req.headers.get(CORRELATION_ID_HEADER));
          if (!req.context.get(QUIET_ERRORS) && !req.context.get(HANDLED_ERROR_STATUSES).includes(apiError.status)) {
            banner.report(apiError);
          }
          return throwError(() => apiError);
        }),
      );
    }),
  );
};

/**
 * Error bodies arrive as objects, strings or Blobs depending on the request's responseType. Only a
 * Blob needs an asynchronous read; everything else is mapped synchronously.
 */
function readErrorBody(raw: unknown): Observable<unknown> {
  if (typeof Blob !== 'undefined' && raw instanceof Blob) {
    return from(blobText(raw).then(parseJsonOrText, () => null));
  }
  return of(typeof raw === 'string' ? parseJsonOrText(raw) : raw);
}

function parseJsonOrText(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function blobText(blob: Blob): Promise<string> {
  if (typeof blob.text === 'function') {
    return blob.text();
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}
