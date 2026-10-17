import { convertToParamMap } from '@angular/router';
import { DEFAULT_QUERY, queryFromParams, queryToParams } from './authorization-query';

describe('authorization query <-> URL', () => {
  it('reads every filter from the query string', () => {
    const q = queryFromParams(
      convertToParamMap({
        status: 'DECLINED',
        responseCode: '51',
        merchantId: 'MERCHANT000001',
        last4: '4444',
        standIn: 'false',
        from: '2026-10-01',
        to: '2026-10-18',
        page: '3',
        size: '50',
      }),
    );
    expect(q).toEqual({
      filter: {
        status: 'DECLINED',
        responseCode: '51',
        merchantId: 'MERCHANT000001',
        last4: '4444',
        standIn: false,
        from: '2026-10-01',
        to: '2026-10-18',
      },
      page: 3,
      size: 50,
    });
  });

  it('drops malformed values instead of sending them', () => {
    const q = queryFromParams(
      convertToParamMap({
        status: 'BOGUS',
        responseCode: '77',
        last4: '44',
        standIn: 'maybe',
        from: '2026-13-40',
        page: '-1',
        size: '7',
      }),
    );
    expect(q).toEqual(DEFAULT_QUERY);
  });

  it('never accepts a full card number as a merchant id', () => {
    expect(queryFromParams(convertToParamMap({ merchantId: '5555555555554444' })).filter).toEqual({});
  });

  it('writes only non-default values and round-trips', () => {
    expect(queryToParams(DEFAULT_QUERY)).toEqual({});
    const q = queryFromParams(convertToParamMap({ status: 'CAPTURED', standIn: 'true', page: '1' }));
    expect(queryToParams(q)).toEqual({ status: 'CAPTURED', standIn: 'true', page: '1' });
    expect(queryFromParams(convertToParamMap(queryToParams(q)))).toEqual(q);
  });
});
