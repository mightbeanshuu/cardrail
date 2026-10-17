import { HttpErrorResponse } from '@angular/common/http';

/**
 * A failed API call, normalised from RFC 7807 `application/problem+json` where the service sent one,
 * and from the transport error otherwise. Business declines are not errors (they are HTTP 200).
 */
export class ApiError extends Error {
  constructor(
    /** HTTP status; 0 when the service could not be reached. */
    readonly status: number,
    readonly title: string,
    readonly detail: string | null,
    readonly type: string | null = null,
    readonly instance: string | null = null,
    /** Field errors from the switch's validation problems (`"field: message"`). */
    readonly fieldErrors: readonly string[] = [],
    readonly correlationId: string | null = null,
    readonly url: string | null = null,
  ) {
    super(detail ? `${title}: ${detail}` : title);
    this.name = 'ApiError';
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }

  /** The demo services limit requests per client address and answer 429 above it. */
  get isRateLimited(): boolean {
    return this.status === 429;
  }
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  errors?: unknown;
  [extension: string]: unknown;
}

export function isProblemDetail(body: unknown): body is ProblemDetail {
  if (body === null || typeof body !== 'object') {
    return false;
  }
  const b = body as Record<string, unknown>;
  return typeof b['title'] === 'string' || typeof b['status'] === 'number' || typeof b['detail'] === 'string';
}

const STATUS_TITLES: Record<number, string> = {
  0: 'Service unreachable',
  400: 'Bad request',
  404: 'Not found',
  409: 'Conflict',
  422: 'Unprocessable request',
  429: 'Too many requests',
  500: 'Internal error',
  502: 'Bad gateway',
  503: 'Service unavailable',
  504: 'Gateway timeout',
};

/**
 * What a visitor reads for a 429, whatever the body says: the cause (their address sent too many
 * requests) and the remedy (wait), with the server's Retry-After when it gave one in seconds.
 */
export function rateLimitDetail(retryAfter: string | null | undefined): string {
  const seconds = retryAfter != null && /^\d{1,4}$/.test(retryAfter.trim()) ? Number(retryAfter.trim()) : null;
  if (seconds != null && seconds > 0) {
    return `Too many requests from your address. Wait ${seconds} ${seconds === 1 ? 'second' : 'seconds'} and try again.`;
  }
  return 'Too many requests from your address. Wait a few seconds and try again.';
}

/** Converts any HttpClient failure into an ApiError. `body` may already be parsed from a Blob. */
export function toApiError(
  response: HttpErrorResponse,
  body: unknown,
  requestCorrelationId: string | null,
): ApiError {
  const correlationId = response.headers?.get('X-Correlation-Id') ?? requestCorrelationId;
  const url = response.url ?? null;
  if (response.status === 429) {
    return new ApiError(
      429,
      STATUS_TITLES[429],
      rateLimitDetail(response.headers?.get('Retry-After')),
      isProblemDetail(body) && body.type && body.type !== 'about:blank' ? body.type : null,
      isProblemDetail(body) ? (body.instance ?? null) : null,
      [],
      correlationId,
      url,
    );
  }

  if (isProblemDetail(body)) {
    const fieldErrors = Array.isArray(body.errors)
      ? body.errors.filter((e): e is string => typeof e === 'string')
      : [];
    return new ApiError(
      typeof body.status === 'number' ? body.status : response.status,
      body.title ?? STATUS_TITLES[response.status] ?? `HTTP ${response.status}`,
      body.detail ?? null,
      body.type && body.type !== 'about:blank' ? body.type : null,
      body.instance ?? null,
      fieldErrors,
      correlationId,
      url,
    );
  }

  if (response.status === 0) {
    return new ApiError(
      0,
      STATUS_TITLES[0],
      'No response from the service. It may be starting up or unreachable, or it does not allow requests from this origin.',
      null,
      null,
      [],
      correlationId,
      url,
    );
  }

  const detail = typeof body === 'string' && body.trim() !== '' && body.length < 300 ? body.trim() : null;
  return new ApiError(
    response.status,
    STATUS_TITLES[response.status] ?? `HTTP ${response.status}`,
    detail,
    null,
    null,
    [],
    correlationId,
    url,
  );
}
