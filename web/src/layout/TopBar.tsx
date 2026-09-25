import { Link, useSearchParams } from 'react-router';
import { LogoMark } from '../components/Logo';
import { ThemeToggle } from '../theme/ThemeToggle';
import { DatasetSelect } from './DatasetSelect';

/** Top bar: app name, dataset selection, the slot for the filter bar (T27) and the theme switch. */
export function TopBar() {
  const [searchParams] = useSearchParams();
  const search = searchParams.toString();
  return (
    <header className="topbar">
      {/* Going "home" keeps the current filters and only drops the selected log. */}
      <Link className="brand" to={{ pathname: '/', search: search ? `?${search}` : '' }}>
        <LogoMark />
        <span className="brand__name">log2code</span>
      </Link>
      <DatasetSelect />
      <div className="topbar__filters" role="search" aria-label="Filters" />
      <ThemeToggle />
    </header>
  );
}
