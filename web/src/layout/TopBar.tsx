import { Link, useSearchParams } from 'react-router';
import { LogoMark } from '../components/Logo';
import { ActiveFilters } from '../filters/ActiveFilters';
import { FilterBar } from '../filters/FilterBar';
import { ThemeToggle } from '../theme/ThemeToggle';
import { DatasetSelect } from './DatasetSelect';

/**
 * Top bar: app name, dataset, filter controls and the theme switch; below them, only while
 * something is filtered, the row of active filter chips (T27).
 */
export function TopBar() {
  const [searchParams] = useSearchParams();
  const search = searchParams.toString();
  return (
    <header className="topbar">
      <div className="topbar__row">
        {/* Going "home" keeps the current filters and only drops the selected log. */}
        <Link className="brand" to={{ pathname: '/', search: search ? `?${search}` : '' }}>
          <LogoMark />
          <span className="brand__name">log2code</span>
        </Link>
        <DatasetSelect />
        <FilterBar />
        <ThemeToggle />
      </div>
      <ActiveFilters />
    </header>
  );
}
