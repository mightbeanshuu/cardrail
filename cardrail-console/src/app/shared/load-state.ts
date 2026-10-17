import { ApiError } from '../core/errors/api-error';

export type LoadState<T> =
  | { readonly status: 'idle' }
  | { readonly status: 'loading' }
  | { readonly status: 'ok'; readonly data: T }
  | { readonly status: 'error'; readonly error: ApiError };

export function asApiError(e: unknown): ApiError {
  return e instanceof ApiError ? e : new ApiError(-1, 'Unexpected error', e instanceof Error ? e.message : String(e));
}
