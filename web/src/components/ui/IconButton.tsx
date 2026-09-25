import type { ReactNode } from 'react';
import { Button, type ButtonProps } from './Button';
import { cx } from './cx';
import { Tooltip } from './Tooltip';

interface IconButtonProps extends Omit<ButtonProps, 'icon' | 'children'> {
  /** Accessible name and tooltip text; required because the button shows no text. */
  label: string;
  icon: ReactNode;
}

export function IconButton({
  label,
  icon,
  variant = 'ghost',
  className,
  ...rest
}: IconButtonProps) {
  return (
    <Tooltip content={label}>
      <Button
        aria-label={label}
        variant={variant}
        icon={icon}
        className={cx('btn--icon', className)}
        {...rest}
      />
    </Tooltip>
  );
}
