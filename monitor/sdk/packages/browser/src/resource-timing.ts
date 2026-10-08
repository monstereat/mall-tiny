export interface ResourceTimingDetails {
  startTime: number;
  dnsMs?: number;
  connectMs?: number;
  tlsMs?: number;
  requestMs?: number;
  responseMs?: number;
  transferSize: number;
  encodedBodySize: number;
  decodedBodySize: number;
  cacheStatus: 'hit' | 'miss' | 'bypass' | 'unknown';
}

function duration(start: number, end: number): number | undefined {
  if (!Number.isFinite(start) || !Number.isFinite(end) || start <= 0 || end < start || end <= 0) return undefined;
  return end - start;
}

export function resourceTimingDetails(
  entry: PerformanceResourceTiming
): ResourceTimingDetails {
  const timing = entry as PerformanceResourceTiming & { deliveryType?: string };
  const transferSize = finiteSize(entry.transferSize);
  const encodedBodySize = finiteSize(entry.encodedBodySize);
  const decodedBodySize = finiteSize(entry.decodedBodySize);
  const deliveryType = timing.deliveryType?.toLowerCase();
  const cacheStatus = deliveryType === 'cache'
    ? 'hit'
    : deliveryType === 'serviceworker'
      ? 'unknown'
      : transferSize > 0
        ? 'miss'
        : encodedBodySize > 0 || decodedBodySize > 0
          ? 'hit'
          : 'unknown';

  return {
    startTime: finiteSize(entry.startTime),
    dnsMs: duration(entry.domainLookupStart, entry.domainLookupEnd),
    connectMs: entry.secureConnectionStart > 0
      ? duration(entry.connectStart, entry.secureConnectionStart)
      : duration(entry.connectStart, entry.connectEnd),
    tlsMs: entry.secureConnectionStart > 0
      ? duration(entry.secureConnectionStart, entry.connectEnd)
      : undefined,
    requestMs: duration(entry.requestStart, entry.responseStart),
    responseMs: duration(entry.responseStart, entry.responseEnd),
    transferSize,
    encodedBodySize,
    decodedBodySize,
    cacheStatus
  };
}

function finiteSize(value: number): number {
  return Number.isFinite(value) && value >= 0 ? value : 0;
}
