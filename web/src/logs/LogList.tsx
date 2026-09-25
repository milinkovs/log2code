import { useVirtualizer } from '@tanstack/react-virtual';
import { ArrowDownWideNarrow, ArrowUpNarrowWide, Rows3, SearchX, Zap } from 'lucide-react';
import { type CSSProperties, type KeyboardEvent, useEffect, useMemo, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { useLogSearch } from '../api/queries';
import type { LogSummary } from '../api/types';
import {
  Button,
  Callout,
  ConfidenceBadge,
  EmptyState,
  Kbd,
  LevelBadge,
  Tooltip,
  cx,
  matchConfidence,
} from '../components/ui';
import {
  PARAM,
  clearFilters,
  hasActiveFilters,
  parseFilters,
  toSearchParams,
} from '../filters/filters';
import { formatDateTime, formatTime } from '../filters/time';
import { Zone } from '../layout/Zone';

/** Must equal `--row-height` in tokens.css; rows have a fixed height, so nothing is measured. */
export const ROW_HEIGHT = 28;
/** Start loading the next page when this many rows remain below the visible ones. */
const PREFETCH_ROWS = 30;
const SKELETON_ROWS = 12;
/** Space above the first and below the last row (--space-1); known to the virtualizer. */
export const LIST_PADDING = 4;

const rowId = (logId: string) => `log-row-${logId}`;

const countLabel = (n: number) => `${n.toLocaleString('en-US')} ${n === 1 ? 'log' : 'logs'}`;

/** Removes duplicates across pages (a page boundary can never repeat a log, but be defensive). */
function uniqueItems(pages: { items: LogSummary[] }[] | undefined): LogSummary[] {
  const seen = new Set<string>();
  const items: LogSummary[] = [];
  for (const page of pages ?? []) {
    for (const item of page.items) {
      if (seen.has(item.logId)) continue;
      seen.add(item.logId);
      items.push(item);
    }
  }
  return items;
}

/**
 * The "Logs" zone: the filtered list (virtualized, infinite scrolling via `searchAfter`), the
 * total count and the sort order switch. Selecting a row navigates to `/logs/:id?<filters>`.
 */
export function LogListZone({ logId }: { logId: string | undefined }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const params = useMemo(() => toSearchParams(searchParams), [searchParams]);
  const filtered = hasActiveFilters(parseFilters(searchParams));
  const query = useLogSearch(params);
  const items = useMemo(() => uniqueItems(query.data?.pages), [query.data]);
  const total = query.data?.pages[0]?.total;

  const select = (id: string, replace: boolean) => {
    const search = searchParams.toString();
    navigate(
      { pathname: `/logs/${encodeURIComponent(id)}`, search: search ? `?${search}` : '' },
      { replace },
    );
  };

  const toggleOrder = () =>
    setSearchParams((previous) => {
      const next = new URLSearchParams(previous);
      if (params.order === 'desc') next.set(PARAM.order, 'asc');
      else next.delete(PARAM.order);
      return next;
    });

  const newestFirst = params.order === 'desc';
  const actions = (
    <>
      <span className="zone__hint" aria-hidden="true">
        <Kbd>↑</Kbd>
        <Kbd>↓</Kbd>
      </span>
      <Tooltip content="Change the sort order">
        <Button
          variant="ghost"
          size="sm"
          icon={
            newestFirst ? (
              <ArrowDownWideNarrow size={14} aria-hidden="true" />
            ) : (
              <ArrowUpNarrowWide size={14} aria-hidden="true" />
            )
          }
          onClick={toggleOrder}
        >
          {newestFirst ? 'Newest first' : 'Oldest first'}
        </Button>
      </Tooltip>
    </>
  );

  let body;
  if (query.isPending) {
    body = <SkeletonRows />;
  } else if (query.isError) {
    body = (
      <div className="log-list__message">
        <Callout tone="danger">
          Could not load logs: {query.error.message}{' '}
          <Button size="sm" onClick={() => void query.refetch()}>
            Retry
          </Button>
        </Callout>
      </div>
    );
  } else if (items.length === 0) {
    body = filtered ? (
      <EmptyState
        icon={SearchX}
        title="No logs match these filters"
        description="Change or clear the filters to see more logs."
      >
        <Button size="sm" onClick={() => setSearchParams((previous) => clearFilters(previous))}>
          Clear filters
        </Button>
      </EmptyState>
    ) : (
      <EmptyState
        icon={Rows3}
        title="No logs"
        description="Nothing is ingested for this dataset yet. Run the ingester to load one."
      />
    );
  } else {
    body = (
      <VirtualLogList
        key={JSON.stringify(params)}
        items={items}
        selectedId={logId}
        onSelect={select}
        hasNextPage={query.hasNextPage}
        isFetchingNextPage={query.isFetchingNextPage}
        nextPageFailed={query.isFetchNextPageError}
        fetchNextPage={query.fetchNextPage}
      />
    );
  }

  return (
    <Zone
      title="Logs"
      icon={Rows3}
      meta={total === undefined ? undefined : `${countLabel(total)} · UTC`}
      actions={actions}
      flush
    >
      {body}
    </Zone>
  );
}

interface VirtualLogListProps {
  items: LogSummary[];
  selectedId: string | undefined;
  onSelect: (logId: string, replace: boolean) => void;
  hasNextPage: boolean;
  isFetchingNextPage: boolean;
  nextPageFailed: boolean;
  /** Stable function from useInfiniteQuery. */
  fetchNextPage: (options?: { cancelRefetch?: boolean }) => unknown;
}

/**
 * The scrolling listbox. Remounted (via `key`) whenever the search changes, so a new result
 * starts at the top. ↑/↓ move the selection and replace the history entry.
 */
function VirtualLogList({
  items,
  selectedId,
  onSelect,
  hasNextPage,
  isFetchingNextPage,
  nextPageFailed,
  fetchNextPage,
}: VirtualLogListProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const showLoaderRow = hasNextPage && !nextPageFailed;
  // eslint-disable-next-line react-hooks/incompatible-library -- the virtualizer re-renders this component itself; nothing here is memoized on its result
  const virtualizer = useVirtualizer({
    count: items.length + (showLoaderRow ? 1 : 0),
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_HEIGHT,
    paddingStart: LIST_PADDING,
    paddingEnd: LIST_PADDING,
    overscan: 12,
    // Rows re-render through React's normal batching; flushSync from inside our effects (scrollToIndex)
    // would trigger "flushSync was called from inside a lifecycle method".
    useFlushSync: false,
  });
  const virtualRows = virtualizer.getVirtualItems();
  const lastVisible = virtualRows.at(-1)?.index ?? -1;

  useEffect(() => {
    if (hasNextPage && !isFetchingNextPage && !nextPageFailed) {
      // cancelRefetch: false, so a second trigger never restarts a page that is already loading.
      if (lastVisible >= items.length - PREFETCH_ROWS) void fetchNextPage({ cancelRefetch: false });
    }
  }, [lastVisible, items.length, hasNextPage, isFetchingNextPage, nextPageFailed, fetchNextPage]);

  const selectedIndex = selectedId ? items.findIndex((i) => i.logId === selectedId) : -1;

  // Bring the selected row into view (deep link, keyboard); no-op when it is already visible.
  useEffect(() => {
    if (selectedIndex >= 0) virtualizer.scrollToIndex(selectedIndex, { align: 'auto' });
  }, [selectedIndex, virtualizer]);

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    event.preventDefault();
    const step = event.key === 'ArrowDown' ? 1 : -1;
    const next =
      selectedIndex < 0 ? 0 : Math.min(items.length - 1, Math.max(0, selectedIndex + step));
    if (next !== selectedIndex) onSelect(items[next].logId, true);
  };

  return (
    <div className="log-list__wrap">
      <div
        ref={scrollRef}
        className="log-list"
        role="listbox"
        aria-label="Logs"
        tabIndex={0}
        aria-activedescendant={selectedIndex >= 0 ? rowId(items[selectedIndex].logId) : undefined}
        onKeyDown={onKeyDown}
      >
        <div className="log-list__canvas" style={{ height: virtualizer.getTotalSize() }}>
          {virtualRows.map((row) => {
            const style = { height: row.size, transform: `translateY(${row.start}px)` };
            if (row.index >= items.length) {
              return <SkeletonRow key="loader" style={style} />;
            }
            const item = items[row.index];
            return (
              <LogRow
                key={item.logId}
                item={item}
                selected={item.logId === selectedId}
                style={style}
                onClick={() => onSelect(item.logId, false)}
              />
            );
          })}
        </div>
      </div>
      {nextPageFailed && (
        <div className="log-list__footer">
          <Callout tone="danger">
            Could not load more logs.{' '}
            <Button size="sm" onClick={() => void fetchNextPage()}>
              Retry
            </Button>
          </Callout>
        </div>
      )}
    </div>
  );
}

interface LogRowProps {
  item: LogSummary;
  selected: boolean;
  style: CSSProperties;
  onClick: () => void;
}

/** One row (docs/design.md §5.2): time, level, service, message, confidence, exception mark. */
function LogRow({ item, selected, style, onClick }: LogRowProps) {
  return (
    <div
      id={rowId(item.logId)}
      role="option"
      aria-selected={selected}
      className={cx('log-row', selected && 'log-row--selected')}
      style={style}
      onClick={onClick}
    >
      <span
        className="log-row__time mono"
        title={item.timestamp ? `${formatDateTime(item.timestamp)} UTC` : undefined}
      >
        {formatTime(item.timestamp)}
      </span>
      <LevelBadge level={item.level} />
      <span className="log-row__service" title={item.service ?? undefined}>
        {item.service}
      </span>
      <span className="log-row__message">{item.message}</span>
      <ConfidenceBadge value={matchConfidence(item.status, item.confidenceLevel)} />
      <span className="log-row__exception">
        {item.hasException && <Zap size={14} role="img" aria-label="Has exception" />}
      </span>
    </div>
  );
}

function SkeletonRow({ style }: { style?: CSSProperties }) {
  return (
    <div className="log-row log-row--skeleton" style={style} aria-hidden="true">
      <span className="skeleton skeleton--time" />
      <span className="skeleton skeleton--level" />
      <span className="skeleton skeleton--service" />
      <span className="skeleton skeleton--message" />
      <span className="skeleton skeleton--confidence" />
    </div>
  );
}

function SkeletonRows() {
  return (
    <div className="log-list log-list--static">
      <span className="sr-only" role="status">
        Loading logs…
      </span>
      {Array.from({ length: SKELETON_ROWS }, (_, i) => (
        <SkeletonRow key={i} />
      ))}
    </div>
  );
}
