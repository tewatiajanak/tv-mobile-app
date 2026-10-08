import { FakeClock, SystemClock } from './clock';

describe('Clock', () => {
  it('SystemClock returns the current time', () => {
    const before = Date.now();
    const now = new SystemClock().now().getTime();
    expect(now).toBeGreaterThanOrEqual(before);
    expect(now).toBeLessThanOrEqual(Date.now());
  });

  it('FakeClock only moves when told to', () => {
    const clock = new FakeClock('2026-10-07T16:26:52.123Z');
    expect(clock.now().toISOString()).toBe('2026-10-07T16:26:52.123Z');
    clock.advanceMs(1_000);
    expect(clock.now().toISOString()).toBe('2026-10-07T16:26:53.123Z');
    clock.set('2027-01-01T00:00:00.000Z');
    expect(clock.now().toISOString()).toBe('2027-01-01T00:00:00.000Z');
  });

  it('FakeClock hands out copies', () => {
    const clock = new FakeClock();
    clock.now().setFullYear(1999);
    expect(clock.now().getUTCFullYear()).toBe(2026);
  });
});
