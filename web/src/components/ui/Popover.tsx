import { Popover as RadixPopover } from 'radix-ui';
import type { ReactElement, ReactNode } from 'react';

interface PopoverProps {
  /** A single element that accepts a ref (usually a Button); it opens the popover. */
  trigger: ReactElement;
  /** Accessible name of the popover content. */
  label: string;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  children: ReactNode;
}

/** Floating panel anchored to a trigger (small forms such as the time range filter). */
export function Popover({ trigger, label, open, onOpenChange, children }: PopoverProps) {
  return (
    <RadixPopover.Root open={open} onOpenChange={onOpenChange}>
      <RadixPopover.Trigger asChild>{trigger}</RadixPopover.Trigger>
      <RadixPopover.Portal>
        <RadixPopover.Content
          className="popover"
          aria-label={label}
          align="start"
          sideOffset={4}
          collisionPadding={8}
        >
          {children}
        </RadixPopover.Content>
      </RadixPopover.Portal>
    </RadixPopover.Root>
  );
}
