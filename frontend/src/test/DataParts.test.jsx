import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import PanelShell from '../components/data/PanelShell.jsx';
import PanelSection from '../components/data/PanelSection.jsx';
import KvList from '../components/data/KvList.jsx';
import StatusDot from '../components/data/StatusDot.jsx';
import { DataSearch, DataTools } from '../components/data/DataTools.jsx';
import {
  Chip, ChipDivider, ChipGroup, ChipRow, ChipSelect, SortChip,
} from '../components/data/Chips.jsx';

describe('PanelShell', () => {
  it('names its title, meta and × from the test id prefix, so Users can keep user-panel-title', () => {
    const onClose = vi.fn();
    render(<PanelShell title="Ada" subtitle="Lite reader" onClose={onClose} testIdPrefix="user-panel">body</PanelShell>);
    expect(screen.getByTestId('user-panel-title')).toHaveTextContent('Ada');
    expect(screen.getByTestId('user-panel-subtitle')).toHaveTextContent('Lite reader');
    fireEvent.click(screen.getByTestId('user-panel-close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('draws the title as a serif heading and the × at 36px, hidden on a phone where the back bar closes', () => {
    render(<PanelShell title="Ada" onClose={vi.fn()} testIdPrefix="p">body</PanelShell>);
    expect(screen.getByRole('heading', { name: 'Ada' }).className).toContain('font-serif');
    const close = screen.getByRole('button', { name: 'Close panel' });
    expect(close.className).toContain('size-9');
    expect(close.className).toContain('max-[639px]:hidden');
  });

  it('draws the body and the footer, and no meta when there is none', () => {
    render(<PanelShell title="Ada" onClose={vi.fn()} testIdPrefix="p" footer={<p>foot</p>}>body</PanelShell>);
    expect(screen.getByText('body')).toBeInTheDocument();
    expect(screen.getByText('foot')).toBeInTheDocument();
    expect(screen.queryByTestId('p-subtitle')).not.toBeInTheDocument();
  });

  it('scrolls the body inside the panel and pins the footer under it', () => {
    render(<PanelShell title="Ada" onClose={vi.fn()} testIdPrefix="p" footer={<p>foot</p>}>body</PanelShell>);
    // The body is the one flexible, scrolling region; the header and footer never shrink.
    const body = screen.getByTestId('p-body').className;
    expect(body).toContain('flex-1');
    expect(body).toContain('min-h-0');
    expect(body).toContain('overflow-y-auto');
    expect(screen.getByTestId('p-footer').className).toContain('shrink-0');
    expect(screen.getByTestId('p').className).toContain('flex-col');
    expect(screen.getByTestId('p').className).toContain('min-h-0');
  });

  it('sticks the footer to the foot of the screen on the phone page', () => {
    render(<PanelShell title="Ada" onClose={vi.fn()} testIdPrefix="p" footer={<p>foot</p>}>body</PanelShell>);
    expect(screen.getByTestId('p-footer').className).toContain('max-[639px]:sticky');
    expect(screen.getByTestId('p-footer').className).toContain('max-[639px]:bottom-0');
  });

  it('draws no footer box when there is no footer', () => {
    render(<PanelShell title="Ada" onClose={vi.fn()} testIdPrefix="p">body</PanelShell>);
    expect(screen.queryByTestId('p-footer')).not.toBeInTheDocument();
  });

  it('makes the title focusable by script, so a newly opened panel can take focus there', () => {
    render(<PanelShell title="Ada" onClose={vi.fn()} testIdPrefix="p">body</PanelShell>);
    const title = screen.getByTestId('p-title');
    expect(title).toHaveAttribute('tabindex', '-1');
    expect(title).toHaveAttribute('data-panel-title');
  });

  it('is never a dialog', () => {
    render(<PanelShell title="Ada" onClose={vi.fn()} testIdPrefix="p">body</PanelShell>);
    expect(document.querySelector('[role="dialog"], [aria-modal]')).toBeNull();
  });
});

describe('PanelSection', () => {
  it('is a labelled section with its action on the label row', () => {
    render(<PanelSection label="Details" action={<button type="button">Edit</button>}>content</PanelSection>);
    const section = screen.getByRole('region', { name: 'Details' });
    expect(within(section).getByRole('button', { name: 'Edit' })).toBeInTheDocument();
    expect(within(section).getByText('content')).toBeInTheDocument();
  });

  it('pads 14x18, and 16 on a phone', () => {
    render(<PanelSection label="Details" testId="s">x</PanelSection>);
    const cls = screen.getByTestId('s').className;
    expect(cls).toContain('px-[18px]');
    expect(cls).toContain('py-[14px]');
    expect(cls).toContain('max-[639px]:px-4');
  });
});

describe('KvList', () => {
  it('lists label and value pairs, mono where asked', () => {
    render(<KvList items={[
      { label: 'Joined', value: '2 Oct 2026', mono: true, testId: 'joined' },
      { label: 'Status', value: 'On' },
    ]}
    />);
    expect(screen.getByText('Joined').tagName).toBe('DT');
    expect(screen.getByTestId('joined')).toHaveTextContent('2 Oct 2026');
    expect(screen.getByTestId('joined').className).toContain('font-mono');
    expect(screen.getByText('On').className).not.toContain('font-mono');
  });

  it('shows an em dash for a null or empty value, never a blank', () => {
    render(<KvList items={[
      { label: 'A', value: null, testId: 'a' },
      { label: 'B', value: '', testId: 'b' },
      { label: 'C', value: 0, testId: 'c' },
    ]}
    />);
    expect(screen.getByTestId('a')).toHaveTextContent('—');
    expect(screen.getByTestId('b')).toHaveTextContent('—');
    expect(screen.getByTestId('c')).toHaveTextContent('0');
  });
});

describe('StatusDot', () => {
  it.each([
    ['go', 'bg-plex-ok'],
    ['amber', 'bg-verdict-marginal'],
    ['coral', 'bg-plex-coral'],
    ['off', 'bg-plex-text-faint'],
  ])('draws the %s tone as %s, with the words beside it', (tone, dotClass) => {
    render(<StatusDot tone={tone} testId="s">Active</StatusDot>);
    const dot = screen.getByTestId('s').firstChild;
    expect(dot.className).toContain(dotClass);
    expect(dot).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByTestId('s')).toHaveTextContent('Active');
  });

  it('quietens the text of an off status', () => {
    render(<StatusDot tone="off" testId="s">Disabled</StatusDot>);
    expect(screen.getByTestId('s').className).toContain('text-plex-text-muted');
  });
});

describe('DataTools', () => {
  it('stacks search above the chips on a phone and lines them up from 640px, by class', () => {
    render(<DataTools testId="tools"><span>x</span></DataTools>);
    const cls = screen.getByTestId('tools').className;
    expect(cls).toContain('flex-wrap');
    expect(cls).toContain('max-[639px]:flex-col');
  });

  it('draws the search box at 280px, full width on a phone, and clears it', () => {
    const onChange = vi.fn();
    render(<DataSearch value="ali" onChange={onChange} placeholder="Search email" testId="q" />);
    expect(screen.getByRole('searchbox', { name: 'Search email' }).parentElement.className).toContain('w-[280px]');
    expect(screen.getByRole('searchbox', { name: 'Search email' }).parentElement.className).toContain('max-[639px]:w-full');
    fireEvent.change(screen.getByTestId('q'), { target: { value: 'bo' } });
    expect(onChange).toHaveBeenLastCalledWith('bo');
    fireEvent.click(screen.getByTestId('q-clear'));
    expect(onChange).toHaveBeenLastCalledWith('');
  });

  it('draws no clear button for an empty search', () => {
    render(<DataSearch value="" onChange={vi.fn()} placeholder="Search email" testId="q" />);
    expect(screen.queryByTestId('q-clear')).not.toBeInTheDocument();
  });
});

describe('Chips', () => {
  it('draws a toggle chip with its count', () => {
    const onClick = vi.fn();
    render(<Chip label="Thin data" count={4} on onClick={onClick} testId="c" />);
    const chip = screen.getByRole('button', { name: /Thin data/ });
    expect(chip).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('c-count')).toHaveTextContent('4');
    fireEvent.click(chip);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('shows a count of zero, and none when no count is given', () => {
    const { rerender } = render(<Chip label="A" count={0} on={false} onClick={vi.fn()} testId="c" />);
    expect(screen.getByTestId('c-count')).toHaveTextContent('0');
    rerender(<Chip label="A" on={false} onClick={vi.fn()} testId="c" />);
    expect(screen.queryByTestId('c-count')).not.toBeInTheDocument();
  });

  it('is 44px tall (the touch-target rule) and does not shrink on a phone', () => {
    render(<Chip label="A" on={false} onClick={vi.fn()} testId="c" />);
    expect(screen.getByTestId('c').className).toContain('max-[639px]:min-h-11');
    expect(screen.getByTestId('c').className).toContain('max-[639px]:shrink-0');
  });

  it('groups chips, scrolls the row sideways on a phone and divides groups', () => {
    render(
      <ChipRow testId="row">
        <ChipGroup label="Status"><Chip label="A" on={false} onClick={vi.fn()} testId="c" /></ChipGroup>
        <ChipDivider />
      </ChipRow>,
    );
    expect(screen.getByRole('group', { name: 'Status' })).toBeInTheDocument();
    expect(screen.getByTestId('row').className).toContain('max-[639px]:overflow-x-auto');
  });

  it('draws a select as a chip that reports the chosen value', () => {
    const onChange = vi.fn();
    render(<ChipSelect label="Region" value="" options={[{ value: '', label: 'All regions' }, { value: '3', label: 'North' }]} onChange={onChange} testId="sel" />);
    fireEvent.change(screen.getByRole('combobox', { name: 'Region' }), { target: { value: '3' } });
    expect(onChange).toHaveBeenCalledWith('3');
    expect(screen.getByTestId('sel').className).toContain('rounded-full');
  });

  it('draws a sort chip only in card mode, listing each column both ways', () => {
    const onChange = vi.fn();
    render(<SortChip options={[{ key: 'name', label: 'Name' }, { key: 'joined', label: 'Joined' }]} sortKey="name" sortDir="asc" onChange={onChange} testId="sort" />);
    expect(screen.getByTestId('sort').parentElement.className).toContain('hidden');
    expect(screen.getByTestId('sort').parentElement.className).toContain('@max-[540px]:inline-flex');
    expect(screen.getAllByRole('option', { hidden: true }).map((o) => o.textContent))
      .toEqual(['Name ▲', 'Name ▼', 'Joined ▲', 'Joined ▼']);
    fireEvent.change(screen.getByTestId('sort'), { target: { value: 'joined:desc' } });
    expect(onChange).toHaveBeenCalledWith('joined', 'desc');
  });
});
