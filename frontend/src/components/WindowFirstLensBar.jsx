import React from 'react';
import PropTypes from 'prop-types';
import { REACH_TIERS, formatLensReadout } from '../utils/reachLens.js';

/**
 * The global lens bar — one control, applied to every window on the page.
 *
 * <h2>It is sticky, and it is never suppressed</h2>
 *
 * <p>Plan §2.5: "Do not suppress the lens bar — that makes a {@code position: sticky} element appear
 * per-user." A user with no home postcode gets the bar, the tiers and the readout; the gate simply
 * finds no drive time to act on and passes everything, which is the visible no-op rule 1 asks for
 * rather than a silently emptied page. The prompt to fix that lives in the rail footer, where the
 * design reserves the slot.
 *
 * <h2>The override is marked twice, and both marks are cheap</h2>
 *
 * <p>An amber "today only" pill states the policy — the choice is discarded at the day roll — and a
 * reset button named for the default it returns to makes it one click to undo. Both key on the same
 * {@code overridden} flag the hook derives, so they cannot disagree, and both are absent while the
 * bar sits on its default. That is deliberately not the "marking the same fact twice" §2.7 warns
 * about: the pill is a statement and the button is an action, and a statement with no route back is
 * the shape this project has already had to fix once ("the setting appeared to do nothing").
 *
 * <p>The reset is rendered rather than hidden with {@code display: none} as the mock does — an
 * inert button in the tab order is a control that goes nowhere.
 *
 * <h2>Role gating</h2>
 *
 * <p>Plan §7 makes the bar a PRO control taking CLAUDE.md's LITE treatment. This is the first gated
 * control in the window-first arm — P7 settled that the attribute rows are <em>not</em> gated and
 * deliberately kept {@code role} out of the card subtree — so the entry point is here and at the
 * shell, and nothing below the shell learns anything about roles. The buttons take a real
 * {@code disabled} as well as the wrapper's {@code pointer-events: none}, because pointer-events
 * does not stop a keyboard.
 *
 * <p>What the gate does to the <em>gate</em> is {@code useReachLens}'s decision and its reasoning is
 * recorded there: LITE is pinned to "Any", so nothing is withheld and the greyed control describes
 * its own true state.
 *
 * @param {object}   props
 * @param {object}   props.lens        the value from {@code useReachLens}
 * @param {number}   props.spotCount   spots drawn across every window — the readout's own count
 * @param {number}   props.windowCount windows drawn
 */
export default function WindowFirstLensBar({ lens, spotCount, windowCount }) {
  const {
    tier, tierId, defaultTier, defaultTierId, weekend, overridden, locked, selectTier,
    resetToDefault,
  } = lens;

  return (
    <div data-testid="window-first-lens" className="wf-lens">
      <div className="wf-lgrp">
        {/* The greying stops HERE, and the upsell below is deliberately outside it. WCAG 1.4.3
            exempts an inactive control from the contrast floor, which is what lets the tiers sit
            at 0.45 — but the "Pro" pill is not inactive, it is the call to action, and inside the
            wrapper it rendered at 3.68:1 against a 4.5:1 floor (measured on the running app;
            12.59:1 outside it). `HotTopicStrip` already ships its upsell as a sibling of the
            blurred content for the same reason. */}
        <div
          data-testid="window-first-lens-controls"
          className={`wf-lens-controls${locked ? ' wf-lens-locked' : ''}`}
        >
          <span className="wf-lens-k" id="wf-lens-label">How far tonight</span>
          <div
            data-testid="window-first-lens-tiers"
            className="wf-seg"
            role="group"
            aria-labelledby="wf-lens-label"
          >
            {REACH_TIERS.map((option) => (
              <button
                key={option.id}
                type="button"
                data-testid="window-first-lens-tier"
                data-tier={option.id}
                // The state a screen reader and a keyboard user actually depend on. `aria-pressed`
                // rather than a radiogroup: these are toggle buttons in a group, not form inputs,
                // and the group's own label is on the container.
                aria-pressed={option.id === tierId}
                disabled={locked}
                onClick={() => selectTier(option.id)}
                className={`wf-seg-btn${option.id === tierId ? ' on' : ''}`}
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>
        {overridden && (
          <span data-testid="window-first-lens-temporary" className="wf-lens-stick">today only</span>
        )}
        {locked && (
          <span data-testid="window-first-lens-upsell" className="wf-lens-pro">Pro</span>
        )}
      </div>

      {overridden && (
        <button
          type="button"
          data-testid="window-first-lens-reset"
          className="wf-lens-reset"
          onClick={resetToDefault}
        >
          {`Back to ${defaultTier.label}`}
        </button>
      )}

      <span data-testid="window-first-lens-readout" className="wf-lens-res">
        {formatLensReadout({
          tierLabel: tier.label,
          // The predicate itself, not `!overridden` — those differ for a locked control, which is
          // pinned to Any and has overridden false while sitting nowhere near today's default.
          isDefault: tierId === defaultTierId,
          weekend,
          spotCount,
          windowCount,
        })}
      </span>
    </div>
  );
}

WindowFirstLensBar.propTypes = {
  lens: PropTypes.shape({
    tier: PropTypes.shape({ id: PropTypes.string, label: PropTypes.string }).isRequired,
    tierId: PropTypes.string.isRequired,
    defaultTier: PropTypes.shape({ label: PropTypes.string }).isRequired,
    defaultTierId: PropTypes.string.isRequired,
    weekend: PropTypes.bool.isRequired,
    overridden: PropTypes.bool.isRequired,
    locked: PropTypes.bool.isRequired,
    selectTier: PropTypes.func.isRequired,
    resetToDefault: PropTypes.func.isRequired,
  }).isRequired,
  spotCount: PropTypes.number.isRequired,
  windowCount: PropTypes.number.isRequired,
};
