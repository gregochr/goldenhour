import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { getSubRegions } from '../api/subRegionApi.js';

/**
 * The reader's S4 sub-region status, for the Map's status buttons and the doors' example places
 * (sub-regions-plan §11 S5a-3, decision 9). One shared read: {@code GET /api/user/settings/sub-regions}, kept OUT
 * of {@code swrCache} (it is personal and a stale lock date would be a wrong sentence), fetched only while the
 * briefing body is shaped, and fetched again whenever {@code version} moves (a save in Settings).
 *
 * <p>The default value is "nothing known, no way to open Settings", so a surface mounted without the provider
 * (every test that predates this) draws no button at all.
 */
const SubRegionStatusContext = createContext({ state: null, openSubRegions: null });

/**
 * @param {object} props
 * @param {boolean} props.enabled whether the briefing body is shaped; false clears the state and reads nothing
 * @param {number} [props.version] moved after a successful sub-region save
 * @param {?Function} [props.onOpen] opens Settings on the picker
 * @param {React.ReactNode} props.children
 */
export function SubRegionStatusProvider({
  enabled, version = 0, onOpen = null, children,
}) {
  // The answer is kept with the read it belongs to, so a body that has stopped being shaped, or a save that moved
  // `version`, shows no state (and so no stale button) until the new read lands; no setState in the effect's body.
  const readKey = `${enabled}:${version}`;
  const [loaded, setLoaded] = useState({ key: null, state: null });
  const state = loaded.key === readKey ? loaded.state : null;
  useEffect(() => {
    if (!enabled) return undefined;
    let cancelled = false;
    getSubRegions()
      .then((answer) => { if (!cancelled) setLoaded({ key: readKey, state: answer }); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [enabled, readKey]);
  // Read only while the body is shaped: when it stops being, the last answer is not offered.
  const value = useMemo(() => ({ state: enabled ? state : null, openSubRegions: onOpen }), [enabled, state, onOpen]);
  return <SubRegionStatusContext.Provider value={value}>{children}</SubRegionStatusContext.Provider>;
}

SubRegionStatusProvider.propTypes = {
  enabled: PropTypes.bool.isRequired,
  version: PropTypes.number,
  onOpen: PropTypes.func,
  children: PropTypes.node,
};

/**
 * The shared status.
 *
 * @returns {{state: ?object, openSubRegions: ?Function}}
 */
export function useSubRegionStatus() {
  return useContext(SubRegionStatusContext);
}
