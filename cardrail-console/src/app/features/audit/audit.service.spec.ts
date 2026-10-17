import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { PROBLEM_HEADERS, provideTestHttp } from '../../../testing/test-providers';
import { ApiError } from '../../core/errors/api-error';
import { AuditService } from './audit.service';

describe('AuditService', () => {
  let service: AuditService;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: provideTestHttp() });
    service = TestBed.inject(AuditService);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  it('returns an intact chain', async () => {
    const result = firstValueFrom(service.verify());
    controller.expectOne('http://switch.test/v1/audit/verify').flush({ intact: true, recordsVerified: 1200 });
    expect(await result).toEqual({ intact: true, recordsVerified: 1200 });
  });

  it('returns the first broken seq', async () => {
    const result = firstValueFrom(service.verify());
    controller
      .expectOne('http://switch.test/v1/audit/verify')
      .flush({ intact: false, recordsVerified: 41, firstBrokenSeq: 42, reason: 'hash mismatch' });
    expect((await result).firstBrokenSeq).toBe(42);
  });

  it('maps problem+json', async () => {
    const result = firstValueFrom(service.verify()).catch((e: unknown) => e);
    controller
      .expectOne('http://switch.test/v1/audit/verify')
      .flush({ title: 'Internal error', status: 500, detail: 'retry' }, { status: 500, statusText: 'x', headers: PROBLEM_HEADERS });
    expect(await result).toMatchObject({ status: 500, title: 'Internal error' } satisfies Partial<ApiError>);
  });
});
