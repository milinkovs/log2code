import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { FileCode2, Plus } from 'lucide-react';
import { describe, expect, it, vi } from 'vitest';
import { Button, Callout, ConfidenceBadge, EmptyState, IconButton, LevelBadge } from '.';
import { matchConfidence } from './matchConfidence';

describe('LevelBadge', () => {
  it('shows the level text and maps FATAL to the error style, UNKNOWN to trace', () => {
    render(
      <>
        <LevelBadge level="FATAL" />
        <LevelBadge level="UNKNOWN" />
        <LevelBadge level="WARN" />
      </>,
    );
    expect(screen.getByText('FATAL')).toHaveClass('level', 'level--error');
    expect(screen.getByText('UNKNOWN')).toHaveClass('level--trace');
    expect(screen.getByText('WARN')).toHaveClass('level--warn');
  });
});

describe('matchConfidence', () => {
  it('collapses status and confidence level into one label', () => {
    expect(matchConfidence('matched', 'high')).toBe('high');
    expect(matchConfidence('matched', 'low')).toBe('low');
    expect(matchConfidence('ambiguous', 'medium')).toBe('ambiguous');
    expect(matchConfidence('unmatched', null)).toBe('unmatched');
    expect(matchConfidence(null, null)).toBe('unmatched');
    expect(matchConfidence('matched', null)).toBe('unmatched');
  });
});

describe('ConfidenceBadge', () => {
  it('shows the label and an optional score with two decimals', () => {
    const { container } = render(<ConfidenceBadge value="high" score={0.9375} />);
    expect(container.firstChild).toHaveClass('confidence--high');
    expect(container).toHaveTextContent('high0.94');
  });
});

describe('Button and IconButton', () => {
  it('Button defaults to a non-submitting secondary button', async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Open on GitHub</Button>);
    const button = screen.getByRole('button', { name: 'Open on GitHub' });
    expect(button).toHaveAttribute('type', 'button');
    expect(button).toHaveClass('btn--secondary', 'btn--md');
    await userEvent.click(button);
    expect(onClick).toHaveBeenCalledOnce();
  });

  it('IconButton is named by its label', () => {
    render(<IconButton label="Add filter" icon={<Plus size={14} />} />);
    expect(screen.getByRole('button', { name: 'Add filter' })).toHaveClass(
      'btn--ghost',
      'btn--icon',
    );
  });
});

describe('EmptyState and Callout', () => {
  it('EmptyState renders title and description', () => {
    render(<EmptyState icon={FileCode2} title="No log selected" description="Pick a log." />);
    expect(screen.getByText('No log selected')).toBeInTheDocument();
    expect(screen.getByText('Pick a log.')).toBeInTheDocument();
  });

  it('a danger Callout is announced, others are notes', () => {
    render(
      <>
        <Callout tone="danger">Broken</Callout>
        <Callout tone="warning">Careful</Callout>
      </>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Broken');
    expect(screen.getByRole('note')).toHaveTextContent('Careful');
  });
});
