import { Check, ChevronDown } from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import type { ReactNode } from 'react';
import { Button } from './Button';
import { cx } from './cx';

export interface MultiSelectOption<T extends string> {
  value: T;
  /** Rendered instead of the raw value (e.g. a LevelBadge). */
  label?: ReactNode;
}

interface MultiSelectProps<T extends string> {
  /** Trigger text and accessible name of the menu, e.g. "Level". */
  label: string;
  options: readonly MultiSelectOption<T>[];
  selected: readonly T[];
  onChange: (next: T[]) => void;
  /** Shown in the menu when there are no options. */
  emptyText?: string;
}

/**
 * Dropdown with checkbox items (docs/design.md §5.2, filter bar). The menu stays open while items
 * are toggled; the trigger shows how many values are selected.
 */
export function MultiSelect<T extends string>({
  label,
  options,
  selected,
  onChange,
  emptyText = 'Nothing to choose from.',
}: MultiSelectProps<T>) {
  const toggle = (value: T, checked: boolean) =>
    // Keep the option order, not the click order, so the URL and the chips stay stable.
    onChange(
      options.map((o) => o.value).filter((v) => (v === value ? checked : selected.includes(v))),
    );

  const count = selected.length;
  return (
    <DropdownMenu.Root modal={false}>
      <DropdownMenu.Trigger asChild>
        <Button
          className={cx('filter-trigger', count > 0 && 'filter-trigger--active')}
          aria-label={count > 0 ? `${label}, ${count} selected` : label}
        >
          {label}
          {count > 0 && <span className="filter-trigger__count">{count}</span>}
          <ChevronDown size={14} aria-hidden="true" className="filter-trigger__chevron" />
        </Button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          className="menu"
          aria-label={label}
          align="start"
          sideOffset={4}
          collisionPadding={8}
        >
          {options.length === 0 && <p className="menu__empty">{emptyText}</p>}
          {options.map((option) => (
            <DropdownMenu.CheckboxItem
              key={option.value}
              className="menu__item"
              checked={selected.includes(option.value)}
              onCheckedChange={(checked) => toggle(option.value, checked === true)}
              // Keep the menu open so several values can be picked in a row.
              onSelect={(event) => event.preventDefault()}
            >
              <span className="menu__check" aria-hidden="true">
                <DropdownMenu.ItemIndicator>
                  <Check size={14} />
                </DropdownMenu.ItemIndicator>
              </span>
              {option.label ?? option.value}
            </DropdownMenu.CheckboxItem>
          ))}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
