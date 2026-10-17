import { HttpParams } from '@angular/common/http';
import { ParamMap, Params } from '@angular/router';
import { isIsoDate, startOfDayInstant, startOfNextDayInstant } from '../../shared/datetime';
import { looksLikePan } from '../../shared/pan';
import { RESPONSE_CODE_LABELS } from '../../shared/response-codes';
import { AUTHORIZATION_STATUSES, AuthorizationStatus } from './authorization.model';

export interface AuthorizationFilter {
  readonly status?: AuthorizationStatus;
  readonly responseCode?: string;
  readonly merchantId?: string;
  readonly last4?: string;
  readonly standIn?: boolean;
  /** UTC calendar dates, inclusive. */
  readonly from?: string;
  readonly to?: string;
}

export interface AuthorizationQuery {
  readonly filter: AuthorizationFilter;
  readonly page: number;
  readonly size: number;
}

export const PAGE_SIZES = [20, 50, 100] as const;
export const DEFAULT_PAGE_SIZE = 20;
export const MERCHANT_ID_PATTERN = /^[A-Za-z0-9 ._-]{1,32}$/;

export const DEFAULT_QUERY: AuthorizationQuery = { filter: {}, page: 0, size: DEFAULT_PAGE_SIZE };

/**
 * Reads the URL query string into a query. Anything malformed is dropped rather than sent, and a
 * value that looks like a full card number is never accepted as a merchant id.
 */
export function queryFromParams(params: ParamMap): AuthorizationQuery {
  const get = (k: string) => params.get(k)?.trim() || undefined;

  const status = get('status');
  const responseCode = get('responseCode');
  const merchantId = get('merchantId');
  const last4 = get('last4');
  const standIn = get('standIn');
  const from = get('from');
  const to = get('to');

  const filter: Record<string, unknown> = {};
  if (status && (AUTHORIZATION_STATUSES as readonly string[]).includes(status)) {
    filter['status'] = status;
  }
  if (responseCode && responseCode in RESPONSE_CODE_LABELS) {
    filter['responseCode'] = responseCode;
  }
  if (merchantId && MERCHANT_ID_PATTERN.test(merchantId) && !looksLikePan(merchantId)) {
    filter['merchantId'] = merchantId;
  }
  if (last4 && /^\d{4}$/.test(last4)) {
    filter['last4'] = last4;
  }
  if (standIn === 'true' || standIn === 'false') {
    filter['standIn'] = standIn === 'true';
  }
  if (isIsoDate(from)) {
    filter['from'] = from;
  }
  if (isIsoDate(to)) {
    filter['to'] = to;
  }

  const page = Number(get('page') ?? '0');
  const size = Number(get('size') ?? DEFAULT_PAGE_SIZE);
  return {
    filter: filter as AuthorizationFilter,
    page: Number.isInteger(page) && page >= 0 ? page : 0,
    size: (PAGE_SIZES as readonly number[]).includes(size) ? size : DEFAULT_PAGE_SIZE,
  };
}

/** The inverse of queryFromParams. Defaults are left out so URLs stay short. */
export function queryToParams(q: AuthorizationQuery): Params {
  const p: Params = {};
  const f = q.filter;
  if (f.status) p['status'] = f.status;
  if (f.responseCode) p['responseCode'] = f.responseCode;
  if (f.merchantId) p['merchantId'] = f.merchantId;
  if (f.last4) p['last4'] = f.last4;
  if (f.standIn !== undefined) p['standIn'] = String(f.standIn);
  if (f.from) p['from'] = f.from;
  if (f.to) p['to'] = f.to;
  if (q.page > 0) p['page'] = String(q.page);
  if (q.size !== DEFAULT_PAGE_SIZE) p['size'] = String(q.size);
  return p;
}

/**
 * Request parameters for `GET /v1/authorizations` (docs/CONTRACTS.md section 2). The API takes
 * `from` inclusive and `to` exclusive, so the chosen UTC days become `from` 00:00:00Z and `to`
 * 00:00:00Z of the day after: both days are included. Results are always newest first.
 */
export function queryToHttpParams(q: AuthorizationQuery): HttpParams {
  let p = new HttpParams();
  const f = q.filter;
  if (f.status) p = p.set('status', f.status);
  if (f.responseCode) p = p.set('responseCode', f.responseCode);
  if (f.merchantId) p = p.set('merchantId', f.merchantId);
  if (f.last4) p = p.set('last4', f.last4);
  if (f.standIn !== undefined) p = p.set('standIn', String(f.standIn));
  if (f.from) p = p.set('from', startOfDayInstant(f.from));
  if (f.to) p = p.set('to', startOfNextDayInstant(f.to));
  return p.set('page', String(q.page)).set('size', String(q.size));
}

export function hasActiveFilter(f: AuthorizationFilter): boolean {
  return Object.values(f).some((v) => v !== undefined);
}
