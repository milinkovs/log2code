import type { CatalogEntryDto } from '../api/types';

const simpleName = (fqn: string | null) => (fqn ? fqn.slice(fqn.lastIndexOf('.') + 1) : '?');

/**
 * `OwnerResource#createOwner`. With the package known, nested classes keep their outer class
 * (`Outer.Inner#run`); without it, only the last segment of the class name is shown.
 */
export function memberLabel(classFqn: string | null, methodName: string | null, pkg?: string) {
  const cls =
    classFqn && pkg && classFqn.startsWith(`${pkg}.`)
      ? classFqn.slice(pkg.length + 1)
      : simpleName(classFqn);
  return `${cls}#${methodName ?? '?'}`;
}

/** Short commit (7 characters) for the project, the artifact version for a library. */
export function shortVersion(entry: Pick<CatalogEntryDto, 'codeUnit'>): string {
  const { type, version } = entry.codeUnit;
  return type === 'project' && /^[0-9a-f]{40}$/.test(version) ? version.slice(0, 7) : version;
}
