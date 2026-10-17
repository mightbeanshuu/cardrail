/** Paged response shape shared by the switch search and clearing record endpoints. */
export interface Page<T> {
  readonly items: readonly T[];
  readonly page: number;
  readonly size: number;
  readonly total: number;
}

export function pageRange(p: Page<unknown>): { first: number; last: number; total: number; pages: number } {
  const pages = p.size > 0 ? Math.max(1, Math.ceil(p.total / p.size)) : 1;
  if (p.total === 0 || p.items.length === 0) {
    return { first: 0, last: 0, total: p.total, pages };
  }
  const first = p.page * p.size + 1;
  return { first, last: first + p.items.length - 1, total: p.total, pages };
}
