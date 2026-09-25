/**
 * Group and panel ids. They are part of the localStorage keys and the saved layouts, so renaming
 * one silently resets that group to its default sizes (harmless, but do it deliberately).
 */
export const WORKSPACE_GROUP = 'workspace';
export const WORKSPACE_PANELS = ['code', 'logs'] as const;

export const CODE_GROUP = 'code-split';
export const CODE_PANELS = ['code-viewer', 'code-context'] as const;

export const LOG_GROUP = 'log-split';
export const LOG_PANELS = ['log-list', 'log-detail'] as const;
