import { Monitor, Moon, Sun } from 'lucide-react';
import { ToggleGroup } from 'radix-ui';
import { Tooltip } from '../components/ui';
import { type ThemePreference, useThemePreference } from './themeStore';

const OPTIONS = [
  { value: 'light', label: 'Light theme', Icon: Sun },
  { value: 'dark', label: 'Dark theme', Icon: Moon },
  { value: 'system', label: 'System theme', Icon: Monitor },
] as const;

/** Light / dark / system switch in the top bar; the choice is remembered (themeStore). */
export function ThemeToggle() {
  const [preference, setPreference] = useThemePreference();
  return (
    <ToggleGroup.Root
      type="single"
      className="segmented"
      aria-label="Theme"
      value={preference}
      // Clicking the active item would clear the value; a theme is always selected.
      onValueChange={(value) => value && setPreference(value as ThemePreference)}
    >
      {OPTIONS.map(({ value, label, Icon }) => (
        <Tooltip key={value} content={label}>
          <ToggleGroup.Item value={value} aria-label={label} className="segmented__item">
            <Icon size={14} aria-hidden="true" />
          </ToggleGroup.Item>
        </Tooltip>
      ))}
    </ToggleGroup.Root>
  );
}
