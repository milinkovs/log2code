import { ArrowLeft } from 'lucide-react';
import { Link, isRouteErrorResponse, useRouteError } from 'react-router';
import { Logo } from './components/Logo';

/** Shown for unknown URLs and for errors thrown while rendering a route. */
export function RouteError() {
  const error = useRouteError();
  const notFound = isRouteErrorResponse(error) && error.status === 404;
  const message = isRouteErrorResponse(error)
    ? `${error.status} ${error.statusText}`
    : error instanceof Error
      ? error.message
      : 'Unexpected error';
  return (
    <main className="error-page">
      <Logo height={32} />
      <h1 className="error-page__title">{notFound ? 'Page not found' : 'Something went wrong'}</h1>
      <p className="error-page__message mono" role="alert">
        {message}
      </p>
      <Link className="btn btn--secondary btn--md" to="/">
        <ArrowLeft size={14} aria-hidden="true" />
        Back to logs
      </Link>
    </main>
  );
}
