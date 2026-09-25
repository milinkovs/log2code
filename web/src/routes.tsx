import type { RouteObject } from 'react-router';
import { AppShell } from './layout/AppShell';
import { RouteError } from './RouteError';

/**
 * Routes: `/` and `/logs/:logId`. Both render the same shell (a layout route), so switching logs
 * does not remount the panels. Filters live in the query string of either route (T27).
 */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppShell />,
    errorElement: <RouteError />,
    children: [
      { index: true, element: null },
      { path: 'logs/:logId', element: null },
    ],
  },
];
