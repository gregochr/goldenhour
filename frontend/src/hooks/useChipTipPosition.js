import { useCallback, useEffect, useRef } from 'react';

/**
 * The tooltip's own CSS `max-width` (`.wf-maplab-tip` in index.css) — the fallback used to clamp
 * its position before the node has rendered once and so cannot yet report a real
 * {@code offsetWidth}.
 */
export const TOOLTIP_WIDTH_FALLBACK = 240;

/**
 * Positions the Map label layer's hover tooltip through a ref and a CSS transform, so a pointer
 * move never renders React (performance review F21: `MapLabels` used to hold the position in state
 * and re-rendered its whole layer on every `pointermove`).
 *
 * <p>Two entry points, both reading the MAP CONTAINER's rect (never the label layer's zero-sized
 * root, PR #733 review) and clamping exactly as the state-based version did:
 * <ul>
 *   <li>{@code place(event)} writes at once, cancelling any pending frame. It is the hover-ENTER
 *       path, so a tooltip never shows for a frame at a stale spot.</li>
 *   <li>{@code move(event)} records the pointer and writes once per animation frame, however many
 *       moves arrive inside it.</li>
 * </ul>
 * {@code attach} is the tooltip node's callback ref: a node that mounts is placed in the same commit
 * from the last recorded pointer, before the browser paints.
 *
 * @param {?object} map the Leaflet map
 * @returns {{attach: Function, place: Function, move: Function}} stable callbacks
 */
export default function useChipTipPosition(map) {
  const nodeRef = useRef(null);
  const pointRef = useRef(null);
  const frameRef = useRef(0);

  const write = useCallback(() => {
    frameRef.current = 0;
    const node = nodeRef.current;
    const point = pointRef.current;
    if (!node || !point) return;
    const wrapRect = map?.getContainer?.()?.getBoundingClientRect();
    if (!wrapRect) return;
    const tipWidth = node.offsetWidth || TOOLTIP_WIDTH_FALLBACK;
    const x = Math.min(point.clientX - wrapRect.left + 13, wrapRect.width - tipWidth - 8);
    const y = Math.max(6, point.clientY - wrapRect.top - 10);
    node.style.transform = `translate(${x}px, ${y}px)`;
  }, [map]);

  const cancel = useCallback(() => {
    if (frameRef.current !== 0) cancelAnimationFrame(frameRef.current);
    frameRef.current = 0;
  }, []);

  const place = useCallback((event) => {
    pointRef.current = { clientX: event.clientX, clientY: event.clientY };
    cancel();
    write();
  }, [cancel, write]);

  const move = useCallback((event) => {
    pointRef.current = { clientX: event.clientX, clientY: event.clientY };
    if (frameRef.current === 0) frameRef.current = requestAnimationFrame(write);
  }, [write]);

  const attach = useCallback((node) => {
    nodeRef.current = node;
    if (node) write();
  }, [write]);

  useEffect(() => cancel, [cancel]);

  return { attach, place, move, refresh: write };
}
