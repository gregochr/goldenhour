import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { render, screen } from '@testing-library/react';
import useConfirmDialog from '../hooks/useConfirmDialog.js';

const sampleConfig = {
  title: 'Confirm',
  message: 'Are you sure?',
  confirmLabel: 'Yes',
  onConfirm: vi.fn(),
};

describe('useConfirmDialog', () => {
  it('starts with null config', () => {
    const { result } = renderHook(() => useConfirmDialog());
    expect(result.current.config).toBeNull();
    expect(result.current.dialogElement).toBeNull();
  });

  it('openDialog sets config', () => {
    const { result } = renderHook(() => useConfirmDialog());
    act(() => result.current.openDialog(sampleConfig));
    expect(result.current.config).toEqual(sampleConfig);
  });

  it('closeDialog clears config', () => {
    const { result } = renderHook(() => useConfirmDialog());
    act(() => result.current.openDialog(sampleConfig));
    act(() => result.current.closeDialog());
    expect(result.current.config).toBeNull();
  });

  it('dialogElement renders when config is set', () => {
    const { result } = renderHook(() => useConfirmDialog());
    act(() => result.current.openDialog(sampleConfig));
    expect(result.current.dialogElement).not.toBeNull();
    render(result.current.dialogElement);
    expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument();
  });

  it('dialogElement is null when config is cleared', () => {
    const { result } = renderHook(() => useConfirmDialog());
    act(() => result.current.openDialog(sampleConfig));
    act(() => result.current.closeDialog());
    expect(result.current.dialogElement).toBeNull();
  });

  it('setConfig allows direct mutation', () => {
    const { result } = renderHook(() => useConfirmDialog());
    act(() => result.current.openDialog(sampleConfig));
    act(() => result.current.setConfig((prev) => ({ ...prev, title: 'Updated' })));
    expect(result.current.config.title).toBe('Updated');
  });
});
