import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { LogDetail, MatchResultDto } from '../api/types';
import { json, mockFetch } from '../test/fetchMock';
import { logDetail, logsRoute, renderApp, searchOf, zone } from '../test/renderApp';

function show(log: LogDetail, path = `/logs/${log.logId}`) {
  mockFetch(logsRoute([]), (url) => {
    if (url.pathname === `/api/logs/${log.logId}`) return json(log);
    if (url.pathname.startsWith('/api/meta')) return json([]);
    return undefined;
  });
  return renderApp(path);
}

const detail = () => zone('Log detail');
/** Value of a field in the definition list, by its label. */
const field = (label: string) =>
  within(detail()).getByText(label, { selector: 'dt' }).nextElementSibling as HTMLElement;

describe('log detail', () => {
  it('shows the full message, the fields and the raw text', async () => {
    const log = logDetail({ message: 'Saving owner Owner[id=11]\nsecond line' });
    show(log);

    const message = await within(detail()).findByTestId('log-message');
    expect(message.textContent).toBe('Saving owner Owner[id=11]\nsecond line');
    expect(within(detail()).getByText('2026-09-23 20:05:44.739 UTC')).toBeInTheDocument();
    expect(within(detail()).getByText('INFO')).toHaveClass('level');
    expect(within(detail()).getByText('high')).toBeInTheDocument();
    expect(within(detail()).getByText('0.94')).toBeInTheDocument();

    expect(field('Service')).toHaveTextContent('customers-service');
    expect(field('Thread')).toHaveTextContent('nio-8081-exec-1');
    expect(field('Logger')).toHaveTextContent(
      'o.s.s.p.c.web.OwnerResource → org.springframework.samples.petclinic.customers.web.OwnerResource',
    );
    expect(field('Trace id')).toHaveTextContent(log.traceId ?? 'missing');
    expect(field('Dataset')).toHaveTextContent('smoke-01');
    expect(field('Log source')).toHaveTextContent('logs/customers-service.log.gz:412');
    expect(field('Code')).toHaveTextContent('OwnerResource.java:89');
    expect(within(field('Code')).getByTitle(log.match?.filePath ?? 'missing')).toBeInTheDocument();

    expect(within(detail()).getByTestId('log-raw').textContent).toBe(log.raw);
    expect(within(detail()).queryByRole('region', { name: 'Exception' })).toBeNull();
  });

  it('clicking the trace id filters by it and keeps the selected log', async () => {
    const log = logDetail();
    const router = show(log, `/logs/${log.logId}?datasetId=smoke-01`);

    await userEvent.click(
      await within(detail()).findByRole('button', { name: log.traceId ?? 'missing' }),
    );
    expect(router.state.location.pathname).toBe(`/logs/${log.logId}`);
    expect(searchOf(router).get('traceId')).toBe(log.traceId);
    expect(searchOf(router).get('datasetId')).toBe('smoke-01');
    expect(screen.getByRole('group', { name: 'Active filters' })).toHaveTextContent('trace:');
    // The log matches its own trace id: no warning.
    expect(within(detail()).queryByRole('note')).toBeNull();
  });

  it('an unmatched log without trace id or resolved logger shows placeholders, no code line', async () => {
    const base = logDetail().match as MatchResultDto;
    show(
      logDetail({
        traceId: null,
        logger: null,
        lineCount: 3,
        match: {
          ...base,
          status: 'unmatched',
          statementId: null,
          confidence: null,
          confidenceLevel: null,
          filePath: null,
          line: null,
        },
      }),
    );

    await within(detail()).findByTestId('log-message');
    expect(within(detail()).getByText('unmatched')).toBeInTheDocument();
    expect(field('Trace id')).toHaveTextContent('—');
    expect(field('Logger')).toHaveTextContent('o.s.s.p.c.web.OwnerResource (not resolved)');
    expect(field('Log source')).toHaveTextContent('(3 lines)');
    expect(within(detail()).queryByText('Code', { selector: 'dt' })).toBeNull();
  });

  it('summarizes the exception: class, message, root cause, first project frame', async () => {
    const frame = (className: string, inProject: boolean, line: number) => ({
      className,
      method: 'handle',
      file: `${className.split('.').at(-1)}.java`,
      line,
      inProject,
      codeUnit: null,
      fileId: null,
      githubUrl: null,
    });
    show(
      logDetail({
        exception: {
          className: 'org.springframework.web.util.NestedServletException',
          rootClass: 'java.lang.IllegalStateException',
          message: 'Request processing failed',
          frames: [frame('org.springframework.web.servlet.FrameworkServlet', false, 1014)],
          causedBy: [
            {
              className: 'java.lang.IllegalStateException',
              message: 'Chaos Monkey',
              frames: [
                frame(
                  'org.springframework.samples.petclinic.customers.web.OwnerResource',
                  true,
                  89,
                ),
                frame('jdk.internal.reflect.Method', false, 580),
              ],
            },
          ],
        },
      }),
    );

    const summary = await within(detail()).findByRole('region', { name: 'Exception' });
    expect(summary).toHaveTextContent(
      'org.springframework.web.util.NestedServletException: Request processing failed',
    );
    expect(summary).toHaveTextContent('Root cause java.lang.IllegalStateException');
    expect(summary).toHaveTextContent(
      'First project frame org.springframework.samples.petclinic.customers.web.OwnerResource.handle(OwnerResource.java:89)',
    );
    expect(summary).toHaveTextContent('3 frames, 1 caused by');
  });

  it('warns when the log does not match the filters and offers to clear them', async () => {
    const log = logDetail();
    const router = show(
      log,
      `/logs/${log.logId}?datasetId=smoke-01&service=vets-service&level=ERROR&order=asc`,
    );

    const note = await within(detail()).findByRole('note');
    expect(note).toHaveTextContent('This log does not match the current filters (service, level).');
    await userEvent.click(within(note).getByRole('button', { name: 'Clear filters' }));
    expect(router.state.location.pathname).toBe(`/logs/${log.logId}`);
    expect(router.state.location.search).toBe('?datasetId=smoke-01&order=asc');
    expect(within(detail()).queryByRole('note')).toBeNull();
  });

  it('a log from another dataset is flagged without a Clear button (Clear keeps the dataset)', async () => {
    const log = logDetail();
    show(log, `/logs/${log.logId}?datasetId=demo-01`);
    const note = await within(detail()).findByRole('note');
    expect(note).toHaveTextContent('(dataset)');
    expect(within(note).queryByRole('button')).toBeNull();
  });

  it('no warning when only the text search is set', async () => {
    const log = logDetail();
    show(log, `/logs/${log.logId}?q=nothing%20like%20it`);
    await within(detail()).findByTestId('log-message');
    expect(within(detail()).queryByRole('note')).toBeNull();
  });
});
