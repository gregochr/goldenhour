import { useMemo } from 'react';
import { useAsk } from '../context/AskContext.jsx';

/**
 * The context a question typed on a surface is sent with, and the chips say it is
 * (`docs/engineering/ask-photocast-plan.md` §2.6, F3).
 *
 * <p>On the Map tab it is what the Map pane last published — the region in scope, the window the pill
 * shows, and the words for both ({@code utils/askMapContext.js}). Anywhere else, and on the Map before
 * the pane has published (no briefing yet), it is "all regions", with the surface's own label. The
 * answer STORES the context it was asked in, so this is read at the moment of asking and never again.
 *
 * <p>One hook for the conversation and the input row — the two pieces every surface (dock, sheet, peek)
 * is built from — so they can never disagree about what the question will carry. {@code scope} is the
 * Ready list's: a single region in scope is that region, anything else is {@code all} (§6 Q8).
 *
 * @param {'map'|'plan'|'coming-up'} view the tab the surface is on
 * @param {string} viewLabel the surface's own chip text, used wherever the Map has nothing to say
 * @returns {{view: string, regionIds: Array<number>, scope: (string|number), viewLabel: string,
 *   windowId: ?string, windowLabel: ?string}} {@code windowId} and {@code windowLabel} are null — never
 *   undefined — wherever there is no window to name
 */
export default function useAskRequestContext(view, viewLabel) {
  const { mapContext } = useAsk();
  return useMemo(() => {
    if (view === 'map' && mapContext) {
      return {
        view,
        regionIds: mapContext.regionIds,
        scope: mapContext.regionIds.length === 1 ? mapContext.regionIds[0] : 'all',
        viewLabel: mapContext.viewLabel,
        windowId: mapContext.windowId,
        windowLabel: mapContext.windowLabel,
      };
    }
    return {
      view, regionIds: [], scope: 'all', viewLabel, windowId: null, windowLabel: null,
    };
  }, [view, viewLabel, mapContext]);
}
