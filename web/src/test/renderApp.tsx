import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import type { LogDetail, LogSummary } from '../api/types';
import { routes } from '../routes';
import { json } from './fetchMock';

/** Renders the whole app (routes, React Query) at `path`; returns the router to inspect the URL. */
export function renderApp(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

export const zone = (name: string) => screen.getByRole('region', { name });

export const searchOf = (router: ReturnType<typeof renderApp>) =>
  new URLSearchParams(router.state.location.search);

export function logSummary(i: number, overrides: Partial<LogSummary> = {}): LogSummary {
  return {
    logId: `log-${String(i).padStart(4, '0')}`,
    timestamp: new Date(Date.UTC(2026, 8, 23, 20, 5, 0, 0) + i * 1000).toISOString(),
    service: 'customers-service',
    level: 'INFO',
    thread: 'main',
    loggerRaw: 'o.s.s.p.c.web.OwnerResource',
    message: `message ${i}`,
    status: 'matched',
    confidence: 0.9,
    confidenceLevel: 'high',
    hasException: false,
    traceId: null,
    classFqn: null,
    methodName: null,
    line: null,
    ...overrides,
  };
}

export function logDetail(overrides: Partial<LogDetail> = {}): LogDetail {
  return {
    logId: 'dcf3be3719357b10ac07fe9b2aed1bd4',
    timestamp: '2026-09-23T20:05:44.739Z',
    timestampRaw: '2026-09-23T20:05:44.739Z',
    datasetId: 'smoke-01',
    sourceFile: 'logs/customers-service.log.gz',
    lineNumber: 412,
    lineCount: 1,
    sequence: 300,
    service: 'customers-service',
    module: 'spring-petclinic-customers-service',
    appName: 'customers-service',
    pid: '1',
    thread: 'nio-8081-exec-1',
    level: 'INFO',
    loggerRaw: 'o.s.s.p.c.web.OwnerResource',
    logger: 'org.springframework.samples.petclinic.customers.web.OwnerResource',
    message: 'Saving owner Owner[id=11]',
    raw: '2026-09-23T20:05:44.739Z  INFO 1 --- [customers-service] [nio-8081-exec-1] o.s.s.p.c.web.OwnerResource : Saving owner Owner[id=11]',
    traceId: '6a3f0c9e1b2c4d5e6a3f0c9e1b2c4d5e',
    spanId: '1b2c4d5e6a3f0c9e',
    exception: null,
    code: { name: 'spring-petclinic-microservices', version: '3858f9c' },
    match: {
      status: 'matched',
      statementId: 'stmt-1',
      confidence: 0.94,
      confidenceLevel: 'high',
      candidates: [],
      scoreBreakdown: {},
      args: [],
      codeUnit: 'spring-petclinic-microservices',
      module: 'spring-petclinic-customers-service',
      classFqn: 'org.springframework.samples.petclinic.customers.web.OwnerResource',
      methodName: 'createOwner',
      filePath:
        'spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java',
      loggingApi: 'slf4j',
      templateKind: 'placeholders',
      line: 89,
      template: 'Saving owner {}',
      githubUrl: null,
    },
    groundTruth: null,
    parserFormat: 'spring-boot-default',
    ingesterVersion: '0.1.0-SNAPSHOT',
    ingestedAt: '2026-09-24T16:06:30Z',
    ...overrides,
  };
}

/**
 * Mock of `GET /api/logs` over `all`: honors `size`, pages with an opaque cursor (base64 offset)
 * and reports `total`. Filtering is the API's job and is asserted on the request instead.
 */
export function logsRoute(all: LogSummary[]) {
  return (url: URL) => {
    if (url.pathname !== '/api/logs') return undefined;
    const size = Number(url.searchParams.get('size') ?? 100);
    const cursor = url.searchParams.get('searchAfter');
    const start = cursor ? Number(atob(cursor)) : 0;
    const items = all.slice(start, start + size);
    return json({
      items,
      nextSearchAfter: items.length ? btoa(String(start + items.length)) : null,
      total: all.length,
      tookMs: 1,
    });
  };
}

/** The `/api/logs` requests made so far, as URLs. */
export const logRequests = (spy: { mock: { calls: unknown[][] } }) =>
  spy.mock.calls
    .map(([input]) => new URL(String(input), 'http://localhost'))
    .filter((url) => url.pathname === '/api/logs');
