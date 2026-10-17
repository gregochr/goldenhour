import { useCallback, useMemo, useState } from 'react';

/**
 * The Map pane's channel into Ask PhotoCast: what the Map last published about the scope and window
 * it is showing (`utils/askMapContext.js`), and the function the pane publishes through. It is
 * unrelated to the conversation's lifecycle — nothing a question, an answer or a clear does touches
 * it, and a conversation never touches it — which is why it is not part of the conversation reducer
 * (`utils/askConversation.js`). {@code AskProvider} calls this hook and puts both onto the same
 * {@code useAsk()} value as before, so no consumer changed.
 *
 * <p>{@code mapContext} is null while no Map pane is mounted: the pane publishes null when it goes.
 * Publishing a context equal to the one held leaves the state alone (the pane publishes on a key, and
 * an unchanged object must not rebuild every consumer of {@code useAsk()}).
 *
 * @returns {{mapContext: ?{regionIds: Array<number>, regionNames: Array<string>, windowId: ?string,
 *   windowLabel: ?string, viewLabel: string}, registerMapContext: function(?object): void}}
 */
export default function useAskMapContext() {
  const [mapContext, setMapContext] = useState(null);
  const registerMapContext = useCallback((next) => {
    setMapContext((prev) => (JSON.stringify(prev) === JSON.stringify(next) ? prev : next));
  }, []);
  return useMemo(() => ({ mapContext, registerMapContext }), [mapContext, registerMapContext]);
}
