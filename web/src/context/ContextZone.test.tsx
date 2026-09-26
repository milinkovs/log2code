import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  CallerDto,
  CatalogEntryDto,
  ControlContextDto,
  LogDetail,
  MethodDetailDto,
  SourceFileDto,
} from '../api/types';
import type { CodeViewerProps } from '../code/codeViewerProps';
import { OWNER_SOURCE, SHA, entry, source } from '../test/catalogFixtures';
import { json, mockFetch, problem } from '../test/fetchMock';
import { logDetail, logSummary, logsRoute, renderApp, searchOf, zone } from '../test/renderApp';
import { CONTEXT_TAB_KEY, readContextTab } from './contextTab';

// Records the lines the context tabs asked the editor to flash (Monaco itself needs a browser).
const focusSeen = vi.hoisted(() => vi.fn<(line: number) => void>());

vi.mock('../code/CodeViewer', async () => {
  const { useEffect } = await import('react');
  return {
    default: function CodeViewerStandIn({
      value,
      path,
      statement,
      focus,
      onFocusDone,
    }: CodeViewerProps) {
      useEffect(() => {
        if (!focus) return;
        focusSeen(focus.line);
        onFocusDone?.(focus.nonce);
      }, [focus, onFocusDone]);
      return (
        <pre
          data-testid="code-viewer"
          data-path={path}
          data-statement={`${statement.start}-${statement.end}`}
        >
          {value}
        </pre>
      );
    },
  };
});

const PET_PATH =
  'spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/PetResource.java';
const MAPPER_PATH =
  'spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/mapper/OwnerEntityMapper.java';
const PKG = 'org.springframework.samples.petclinic.customers.web';

/** `control` of statement #1 (`OwnerResource.updateOwner`), as the analyzer stored it (T11, T13). */
const OWNER_CONTROL: ControlContextDto = {
  conditions: [],
  earlyExits: [],
  preceding: [
    { kind: 'call', text: 'ownerEntityMapper.map(ownerModel, ownerRequest);', line: 88 },
    {
      kind: 'var_decl',
      text: 'final Owner ownerModel = ownerRepository.findById(ownerId).orElseThrow(() -> new ResourceNotFoundException("Owner " + ownerId + " not found"));',
      line: 86,
    },
  ],
  callsBefore: [
    {
      line: 88,
      text: 'ownerEntityMapper.map(ownerModel, ownerRequest)',
      target: 'ownerEntityMapper.map',
      targetMethodId: 'm-map',
      resolved: true,
    },
    {
      line: 86,
      text: 'ownerRepository.findById(ownerId)',
      target: 'ownerRepository.findById',
      targetMethodId: null,
      resolved: true,
    },
  ],
};

const OWNER = entry({ control: OWNER_CONTROL });

/** Statement #2, `Saving pet {}` in the private `PetResource.save`: a method with callers. */
const PET = entry({
  statementId: 'stmt-pet',
  filePath: PET_PATH,
  fileId: 'file-pet',
  classFqn: `${PKG}.PetResource`,
  classBinary: `${PKG}.PetResource`,
  methodName: 'save',
  methodSignature: 'save(Pet,PetRequest)',
  methodId: 'm-save',
  line: 84,
  endLine: 84,
  methodStartLine: 78,
  methodEndLine: 86,
  template: 'Saving pet {}',
  control: { conditions: [], earlyExits: [], preceding: [], callsBefore: [] },
});

const LIBRARY = entry({
  statementId: 'stmt-lib',
  codeUnit: { type: 'dependency', name: 'com.netflix.eureka:eureka-client', version: '2.0.5' },
  service: null,
  filePath: 'com/netflix/discovery/DiscoveryClient.java',
  fileId: 'file-discovery',
  classFqn: 'com.netflix.discovery.DiscoveryClient',
  methodName: 'shutdown',
  methodId: null,
  control: null,
});

function method(overrides: Partial<MethodDetailDto>): MethodDetailDto {
  return {
    methodId: 'm',
    codeUnit: { type: 'project', name: 'spring-petclinic-microservices', version: SHA },
    module: 'spring-petclinic-customers-service',
    service: 'customers-service',
    fileId: 'file-pet',
    filePath: PET_PATH,
    classFqn: `${PKG}.PetResource`,
    classBinary: null,
    methodName: 'm',
    methodSignature: 'm()',
    startLine: 1,
    endLine: 2,
    annotations: [],
    hasLogStatements: false,
    calls: [],
    calledBy: [],
    callerCount: 0,
    ...overrides,
  };
}

const caller = (overrides: Partial<CallerDto>): CallerDto => ({
  methodId: 'c',
  classFqn: `${PKG}.PetResource`,
  methodName: 'c',
  fileId: 'file-pet',
  line: 1,
  callerCount: 0,
  annotations: [],
  ...overrides,
});

const METHODS: MethodDetailDto[] = [
  method({ methodId: 'm-save', methodName: 'save', callerCount: 2, hasLogStatements: true }),
  method({
    methodId: 'm-map',
    fileId: 'file-mapper',
    filePath: MAPPER_PATH,
    classFqn: `${PKG}.mapper.OwnerEntityMapper`,
    methodName: 'map',
    startLine: 20,
    endLine: 30,
    callerCount: 2,
  }),
  method({
    methodId: 'm-update',
    classFqn: `${PKG}.OwnerResource`,
    methodName: 'updateOwner',
    annotations: ['PutMapping', 'ResponseStatus'],
  }),
];

/** `save` ← `processCreationForm` (REST) and ← `copyPet` ← `importPets` (no callers, not REST). */
const CALLERS: Record<string, CallerDto[]> = {
  'm-save': [
    caller({
      methodId: 'm-create',
      methodName: 'processCreationForm',
      line: 65,
      annotations: ['PostMapping', 'ResponseStatus'],
    }),
    caller({ methodId: 'm-copy', methodName: 'copyPet', line: 101, callerCount: 1 }),
  ],
  'm-copy': [caller({ methodId: 'm-import', methodName: 'importPets', line: 120 })],
};

const SOURCES: SourceFileDto[] = [
  source('file-owner', OWNER_SOURCE),
  source('file-pet', OWNER_SOURCE, PET_PATH),
  source('file-mapper', OWNER_SOURCE, MAPPER_PATH),
];

function show(log: LogDetail, entries: CatalogEntryDto[], path?: string) {
  const list = [logSummary(1, { logId: log.logId }), logSummary(2)];
  const fetchSpy = mockFetch(logsRoute(list), (url) => {
    const p = url.pathname;
    if (p === `/api/logs/${log.logId}`) return json(log);
    if (p === '/api/logs/log-0002') return json(logDetail({ logId: 'log-0002', match: null }));
    if (p.endsWith('/candidates')) return json([]);
    const catalog = entries.find((e) => p === `/api/catalog/${e.statementId}`);
    if (catalog) return json(catalog);
    const file = SOURCES.find((s) => p === `/api/sources/${s.fileId}`);
    if (file) return json(file);
    const callers = p.match(/^\/api\/methods\/([^/]+)\/callers$/);
    if (callers) return json(CALLERS[callers[1]] ?? []);
    const found = METHODS.find((m) => p === `/api/methods/${m.methodId}`);
    if (found) return json(found);
    if (p.startsWith('/api/methods/')) return problem(404, 'method not found');
    if (p.startsWith('/api/meta')) return json([]);
    return undefined;
  });
  const router = renderApp(path ?? `/logs/${log.logId}?datasetId=smoke-01`);
  return { router, fetchSpy };
}

const logWith = (statementId: string) =>
  logDetail({ match: { ...(logDetail().match as NonNullable<LogDetail['match']>), statementId } });

const context = () => zone('Context');
const viewer = () => within(zone('Code')).findByTestId('code-viewer');
const methodRequests = (spy: ReturnType<typeof mockFetch>) =>
  spy.mock.calls
    .map(([input]) => new URL(String(input), 'http://localhost').pathname)
    .filter((p) => p.startsWith('/api/methods'));

beforeEach(() => {
  focusSeen.mockClear();
  window.localStorage.clear();
});

describe('context zone', () => {
  it('without a selected log it asks to pick one', () => {
    mockFetch(logsRoute([]), (url) =>
      url.pathname.startsWith('/api/meta') ? json([]) : undefined,
    );
    renderApp('/');
    expect(within(context()).getByRole('tab', { name: 'Conditions and flow' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(within(context()).getByText('No log selected')).toBeInTheDocument();
  });

  it('shows the preceding statements of the method, nearest first, and jumps to a line (AC1)', async () => {
    const { fetchSpy } = show(logWith('stmt-1'), [OWNER]);
    await viewer();

    const preceding = within(
      await within(context()).findByRole('region', { name: 'Preceding statements' }),
    );
    const rows = preceding.getAllByRole('button');
    expect(rows.map((r) => r.textContent)).toEqual([
      '88callownerEntityMapper.map(ownerModel, ownerRequest);',
      expect.stringMatching(/^86var declfinal Owner ownerModel = /),
    ]);
    expect(within(context()).getByText('nearest first', { exact: false })).toBeInTheDocument();
    expect(
      within(context()).getByText('The statement is not inside a condition, loop, or handler.'),
    ).toBeInTheDocument();

    await userEvent.click(rows[1]);
    expect(focusSeen).toHaveBeenLastCalledWith(86);
    // The tab reads the catalog entry the code zone already loaded: no request of its own.
    expect(methodRequests(fetchSpy)).toEqual([]);
  });

  it('indents nested conditions, shows the else branch as "not:" and the early exits', async () => {
    const nested = entry({
      control: {
        conditions: [
          { kind: 'if', text: 'owner.isActive()', line: 29, negated: false },
          { kind: 'else', text: 'owner.getPets().isEmpty()', line: 32, negated: true },
        ],
        earlyExits: [{ text: 'normalized == null', line: 22, exitKind: 'return' }],
        preceding: [],
        callsBefore: [],
      },
    });
    show(logWith('stmt-1'), [nested]);

    const conditions = within(
      await within(context()).findByRole('region', { name: 'Conditions' }),
    ).getAllByRole('button');
    expect(conditions.map((c) => c.textContent)).toEqual([
      '29ifowner.isActive()',
      '32elsenot: owner.getPets().isEmpty()',
    ]);
    expect(conditions[1].style.getPropertyValue('--depth')).toBe('1');

    const exits = within(context()).getByRole('region', { name: 'Reached only if not' });
    expect(within(exits).getByRole('button')).toHaveTextContent('22normalized == nullreturn');
    await userEvent.click(within(exits).getByRole('button'));
    expect(focusSeen).toHaveBeenLastCalledWith(22);
  });

  it('a call resolved to the project opens its method; "Back to log" returns to the statement', async () => {
    const { router } = show(logWith('stmt-1'), [OWNER]);
    expect(await viewer()).toHaveAttribute('data-statement', '89-89');

    // Only the resolved call to a project method is a link; the Spring Data call only jumps.
    expect(within(context()).getAllByRole('button', { name: /^Open / })).toHaveLength(1);
    await userEvent.click(
      within(context()).getByRole('button', { name: 'Open ownerEntityMapper.map' }),
    );

    await waitFor(() => expect(searchOf(router).get('at')).toBe('file-mapper:20'));
    const code = within(zone('Code'));
    expect(await code.findByText('Opened from context')).toBeInTheDocument();
    expect(await code.findByText('OwnerEntityMapper.java')).toBeInTheDocument();
    expect(await viewer()).toHaveAttribute('data-path', `file-mapper/${MAPPER_PATH}`);
    expect(await viewer()).toHaveAttribute('data-statement', '20-20');
    // The GitHub link points at the log's statement, not at the opened file.
    expect(code.queryByRole('link', { name: /GitHub/ })).toBeNull();

    await userEvent.click(code.getByRole('button', { name: 'Back to log' }));
    expect(searchOf(router).get('at')).toBeNull();
    expect(searchOf(router).get('datasetId')).toBe('smoke-01');
    await waitFor(async () =>
      expect(await viewer()).toHaveAttribute('data-path', `file-owner/${entry().filePath}`),
    );
    expect(code.queryByText('Opened from context')).toBeNull();
  });

  it('a line clicked while another file is open goes back to the statement file first', async () => {
    const log = logWith('stmt-1');
    const { router } = show(log, [OWNER], `/logs/${log.logId}?at=file-mapper:20`);
    expect(await viewer()).toHaveAttribute('data-path', `file-mapper/${MAPPER_PATH}`);

    const calls = await within(context()).findByRole('region', { name: 'Calls before' });
    await userEvent.click(within(calls).getByRole('button', { name: /ownerRepository\.findById/ }));
    expect(searchOf(router).get('at')).toBeNull();
    await waitFor(() => expect(focusSeen).toHaveBeenLastCalledWith(86));
    expect(await viewer()).toHaveAttribute('data-path', `file-owner/${entry().filePath}`);
  });

  it('the callers tab makes no request until it is opened', async () => {
    const { fetchSpy } = show(logWith('stmt-pet'), [PET]);
    await viewer();
    await within(context()).findByRole('region', { name: 'Preceding statements' });
    expect(methodRequests(fetchSpy)).toEqual([]);

    await userEvent.click(within(context()).getByRole('tab', { name: 'Callers' }));
    await within(context()).findByText('PetResource#processCreationForm');
    expect(methodRequests(fetchSpy)).toEqual([
      '/api/methods/m-save',
      '/api/methods/m-save/callers',
    ]);
    expect(window.localStorage.getItem(CONTEXT_TAB_KEY)).toBe('callers');
  });

  it('expands the callers tree two levels deep, loading each level on ▸ (AC2)', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'callers');
    const { fetchSpy } = show(logWith('stmt-pet'), [PET]);

    const tree = within(await within(context()).findByRole('list', { name: 'Callers' }));
    // The root is the statement's own method, open from the start.
    expect(
      await tree.findByRole('button', { name: 'Collapse callers of PetResource#save' }),
    ).toHaveAttribute('aria-expanded', 'true');
    expect(tree.getByText('2 callers')).toBeInTheDocument();

    const create = (await tree.findByText('PetResource#processCreationForm')).closest(
      '.tree-row',
    ) as HTMLElement;
    expect(within(create).getByText(':65')).toBeInTheDocument();
    expect(within(create).getByText('REST entry')).toBeInTheDocument();
    expect(create.style.getPropertyValue('--depth')).toBe('1');

    expect(methodRequests(fetchSpy)).not.toContain('/api/methods/m-copy/callers');
    await userEvent.click(
      tree.getByRole('button', { name: 'Expand callers of PetResource#copyPet' }),
    );
    const imports = (await tree.findByText('PetResource#importPets')).closest(
      '.tree-row',
    ) as HTMLElement;
    expect(imports.style.getPropertyValue('--depth')).toBe('2');
    expect(within(imports).getByText('no callers in the project')).toBeInTheDocument();
    expect(within(imports).queryByRole('button', { name: /Expand/ })).toBeNull();
    expect(methodRequests(fetchSpy)).toContain('/api/methods/m-copy/callers');

    await userEvent.click(
      tree.getByRole('button', { name: 'Collapse callers of PetResource#copyPet' }),
    );
    expect(tree.queryByText('PetResource#importPets')).toBeNull();
  });

  it('navigates code → caller → back to log (AC3)', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'callers');
    const { router } = show(logWith('stmt-pet'), [PET]);
    expect(await viewer()).toHaveAttribute('data-statement', '84-84');

    const tree = within(await within(context()).findByRole('list', { name: 'Callers' }));
    await userEvent.click(await tree.findByRole('button', { name: /processCreationForm/ }));
    expect(searchOf(router).get('at')).toBe('file-pet:65');
    await waitFor(async () => expect(await viewer()).toHaveAttribute('data-statement', '65-65'));
    expect(tree.getByRole('button', { name: /processCreationForm/ })).toHaveAttribute(
      'aria-current',
      'location',
    );

    await userEvent.click(within(zone('Code')).getByRole('button', { name: 'Back to log' }));
    expect(searchOf(router).get('at')).toBeNull();
    await waitFor(async () => expect(await viewer()).toHaveAttribute('data-statement', '84-84'));

    // Browser Back returns to the caller, Forward to the log again.
    await act(() => router.navigate(-1));
    expect(searchOf(router).get('at')).toBe('file-pet:65');
    await act(() => router.navigate(1));
    expect(searchOf(router).get('at')).toBeNull();

    // The root is the statement's method: it returns to the log and flashes the method start.
    await userEvent.click(tree.getByRole('button', { name: /processCreationForm/ }));
    await waitFor(() => expect(searchOf(router).get('at')).toBe('file-pet:65'));
    await userEvent.click(tree.getByRole('button', { name: 'PetResource#save' }));
    expect(searchOf(router).get('at')).toBeNull();
    await waitFor(() => expect(focusSeen).toHaveBeenLastCalledWith(78));
  });

  it('a REST method without callers is marked as the entry point', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'callers');
    show(logWith('stmt-1'), [entry({ methodId: 'm-update' })]);
    const tree = within(await within(context()).findByRole('list', { name: 'Callers' }));
    const root = (await tree.findByText('OwnerResource#updateOwner')).closest('.tree-row');
    expect(within(root as HTMLElement).getByText('REST entry')).toBeInTheDocument();
    expect(tree.queryByRole('button', { name: /Expand|Collapse/ })).toBeNull();
  });

  it('library code has no call graph', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'callers');
    show(logWith('stmt-lib'), [LIBRARY]);
    expect(
      await within(context()).findByText(
        "The call graph is not available for library code. It covers the project's own methods.",
      ),
    ).toBeInTheDocument();
  });

  it('a project method missing from the call graph asks to run the analyzer', async () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'callers');
    show(logWith('stmt-1'), [entry({ methodId: 'm-gone' })]);
    expect(await within(context()).findByText('No call graph for this method')).toBeInTheDocument();
  });

  it('a statement without control context says so', async () => {
    show(logWith('stmt-lib'), [LIBRARY]);
    expect(
      await within(context()).findByText('No control flow for this statement'),
    ).toBeInTheDocument();
  });

  it('an unmatched log has no statement to show context for', async () => {
    const log = logDetail({
      match: {
        ...(logDetail().match as NonNullable<LogDetail['match']>),
        status: 'unmatched',
        statementId: null,
      },
    });
    show(log, []);
    expect(await within(context()).findByText('No statement for this log')).toBeInTheDocument();
  });

  it('selecting another log leaves the opened location', async () => {
    const log = logWith('stmt-1');
    const { router } = show(
      log,
      [OWNER],
      `/logs/${log.logId}?datasetId=smoke-01&at=file-mapper:20`,
    );
    await within(zone('Code')).findByText('Opened from context');
    await userEvent.click(await screen.findByText('message 2'));
    await waitFor(() => expect(router.state.location.pathname).toBe('/logs/log-0002'));
    expect(searchOf(router).get('at')).toBeNull();
    expect(searchOf(router).get('datasetId')).toBe('smoke-01');
  });
});

describe('context tab storage', () => {
  it('falls back to the first tab for unknown values and unusable storage', () => {
    window.localStorage.setItem(CONTEXT_TAB_KEY, 'unknown-tab');
    expect(readContextTab()).toBe('flow');
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(readContextTab()).toBe('flow');
    spy.mockRestore();
  });
});
