import { authorization } from '../../../testing/fixtures';
import { authorizationTimeline } from './authorization-timeline';

describe('authorizationTimeline', () => {
  it('shows an open hold as a pending step', () => {
    const steps = authorizationTimeline(authorization());
    expect(steps.map((s) => s.label)).toEqual(['Approved', 'Awaiting capture or reversal']);
    expect(steps[1].tone).toBe('pending');
  });

  it('shows a partial capture with the released remainder', () => {
    const steps = authorizationTimeline(
      authorization({ status: 'CAPTURED', capturedAmountMinor: 20000, capturedAt: '2026-10-18T10:00:00Z' }),
    );
    expect(steps.map((s) => s.label)).toEqual(['Approved', 'Captured']);
    expect(steps[1].detail).toBe('₹200.00 captured; ₹50.00 released.');
  });

  it('shows a stand-in decline with its reason', () => {
    const [step] = authorizationTimeline(
      authorization({ status: 'DECLINED', responseCode: '91', standIn: true, authCode: null, declineReason: 'over stand-in limit' }),
    );
    expect(step.label).toBe('Declined');
    expect(step.tone).toBe('failed');
    expect(step.detail).toContain('91 Issuer unavailable, by stand-in processing (STIP). Reason: over stand-in limit');
  });
});
