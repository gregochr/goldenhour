import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import PasswordChecklist from '../components/auth/PasswordChecklist.jsx';

const CHECKS = [
  { label: 'At least 8 characters', ok: true },
  { label: 'One number', ok: false },
];

describe('PasswordChecklist', () => {
  it('shows a met rule as a check in plex-ok', () => {
    render(<PasswordChecklist checks={CHECKS} />);

    const met = screen.getByText('At least 8 characters').closest('li');
    expect(met).toHaveTextContent('✓');
    expect(met).toHaveClass('text-plex-ok');
    expect(met).toHaveAttribute('data-ok', 'true');
    expect(met.className).not.toMatch(/green|verdict/);
  });

  it('shows an unmet rule as a hollow circle in secondary ink, never a cross in muted ink', () => {
    render(<PasswordChecklist checks={CHECKS} />);

    const unmet = screen.getByText('One number').closest('li');
    expect(unmet).toHaveTextContent('○');
    expect(unmet).not.toHaveTextContent('✗');
    expect(unmet).toHaveClass('text-plex-text-secondary');
    expect(unmet.className).not.toMatch(/text-plex-text-muted/);
    expect(unmet).toHaveAttribute('data-ok', 'false');
  });

  it('keeps the given order and renders one item per rule', () => {
    render(<PasswordChecklist checks={CHECKS} testId="list" />);

    const items = screen.getByTestId('list').querySelectorAll('li');
    expect([...items].map((li) => li.textContent)).toEqual(['✓At least 8 characters', '○One number']);
  });

  it('defaults its test id and renders an empty list for no checks', () => {
    render(<PasswordChecklist checks={[]} />);
    expect(screen.getByTestId('password-checklist').children).toHaveLength(0);
  });
});
