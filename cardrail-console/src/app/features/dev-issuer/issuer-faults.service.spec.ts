import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { PROBLEM_HEADERS, provideTestHttp } from '../../../testing/test-providers';
import { ApiError } from '../../core/errors/api-error';
import { IssuerFaultsService } from './issuer-faults.service';

describe('IssuerFaultsService', () => {
  const url = 'http://switch.test/dev/issuer/faults';
  let service: IssuerFaultsService;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: provideTestHttp() });
    service = TestBed.inject(IssuerFaultsService);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  it('reads the current settings', async () => {
    const result = firstValueFrom(service.current());
    const req = controller.expectOne(url);
    expect(req.request.method).toBe('GET');
    req.flush({ latencyMs: 0, failureRate: 0 });
    expect(await result).toEqual({ latencyMs: 0, failureRate: 0 });
  });

  it('PUTs latency and failure rate in the controller body shape', () => {
    service.set({ latencyMs: 2000, failureRate: 0.25 }).subscribe();
    const req = controller.expectOne(url);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ latencyMs: 2000, failureRate: 0.25 });
    req.flush({ latencyMs: 2000, failureRate: 0.25 });
  });

  it('DELETEs to restore defaults', () => {
    service.reset().subscribe();
    const req = controller.expectOne(url);
    expect(req.request.method).toBe('DELETE');
    req.flush({ latencyMs: 0, failureRate: 0 });
  });

  it('maps a validation problem', async () => {
    const result = firstValueFrom(service.set({ latencyMs: -1, failureRate: 2 })).catch((e: unknown) => e);
    controller.expectOne(url).flush(
      { title: 'Invalid request', status: 400, errors: ['failureRate: must be less than or equal to 1.0'] },
      { status: 400, statusText: 'Bad Request', headers: PROBLEM_HEADERS },
    );
    const error = (await result) as ApiError;
    expect(error.fieldErrors).toEqual(['failureRate: must be less than or equal to 1.0']);
  });
});
