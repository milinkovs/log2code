import type { ComponentProps, ReactNode } from 'react';
import { cx } from './cx';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost';
export type ButtonSize = 'sm' | 'md';

export interface ButtonProps extends ComponentProps<'button'> {
  /** `primary` at most once per view; `secondary` is the default; `ghost` for toolbars. */
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** A lucide icon element, rendered before the label. */
  icon?: ReactNode;
}

export function Button({
  variant = 'secondary',
  size = 'md',
  icon,
  className,
  children,
  type = 'button',
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      className={cx('btn', `btn--${variant}`, `btn--${size}`, className)}
      {...rest}
    >
      {icon}
      {children}
    </button>
  );
}
