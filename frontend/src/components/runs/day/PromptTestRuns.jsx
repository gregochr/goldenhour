import PropTypes from 'prop-types';
import {
  TEST_SWATCH, isGithubUrl, promptTestCostText, promptTestMeta, promptTestOutcome,
} from '../../../utils/runsCost.js';

/** The outcome's colour: green for a pass, coral for a failure, quiet for anything else. */
const OUTCOME_TONE = { ok: 'text-plex-ok', bad: 'text-plex-coral', quiet: 'text-plex-text-secondary' };

/** The suite's short word for a row ({@code sky} or {@code ask}). */
const suiteWord = (suite) => String(suite).toLowerCase();

/**
 * One CI prompt-regression run: the PR and title (or "Manual run"), the suite, the outcome, the cost (or why there is
 * none) and a link to the GitHub run, with the cases, tokens and attempt in a line beneath. A phone (639px and under)
 * wraps the line onto its own row.
 */
function PromptTestRow({ row, index }) {
  const outcome = promptTestOutcome(row);
  const priced = row.usageState === 'KNOWN';
  return (
    <li
      className="grid grid-cols-[auto_minmax(0,1fr)_auto_auto_auto] items-center gap-x-3.5 gap-y-1 border-t border-plex-border py-2 text-[13px] first:border-t-0 max-[639px]:grid-cols-[minmax(0,1fr)_auto]"
      data-testid={`runs-day-prompt-test-${index}`}
    >
      <span className="font-mono text-test-sky" data-testid={`runs-day-prompt-test-${index}-pr`}>
        {row.prNumber != null ? `PR #${row.prNumber}` : 'Manual run'}
      </span>
      <span className="min-w-0 justify-self-start text-left text-plex-text max-[639px]:col-span-full max-[639px]:row-start-2">
        {row.prNumber != null && row.prTitle ? `${row.prTitle} ` : ''}
        <span className="font-mono text-[11px] text-plex-text-muted">{'·'} {suiteWord(row.suite)}</span>
      </span>
      <span className={`font-mono text-[11px] ${OUTCOME_TONE[outcome.tone]}`} data-testid={`runs-day-prompt-test-${index}-outcome`}>
        {outcome.text}
      </span>
      <span className={`font-mono ${priced ? 'text-plex-text' : 'text-plex-text-secondary'}`} data-testid={`runs-day-prompt-test-${index}-cost`}>
        {promptTestCostText(row)}
      </span>
      {isGithubUrl(row.htmlUrl) && (
        <a
          href={row.htmlUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="font-mono text-[11.5px] text-test-sky underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
          data-testid={`runs-day-prompt-test-${index}-link`}
        >
          run {'↗'}
        </a>
      )}
      <span className="col-span-full font-mono text-[11px] text-plex-text-muted" data-testid={`runs-day-prompt-test-${index}-meta`}>
        {promptTestMeta(row)}
      </span>
    </li>
  );
}

PromptTestRow.propTypes = { row: PropTypes.object.isRequired, index: PropTypes.number.isRequired };

/**
 * The Day view's "Prompt test runs" section (ci-spend-plan.md C3): the CI prompt-regression runs that started on the
 * day, listed after the app's runs. It is its own card, outside the run filters, the summary and the strip: CI spend
 * is counted apart from the app's. Nothing is rendered when the day has none.
 *
 * @param {object} props
 * @param {Array<object>|undefined} props.runs the served {@code promptTestRuns}
 */
export default function PromptTestRuns({ runs }) {
  if (!runs || runs.length === 0) return null;
  return (
    <section
      className="rounded-xl border border-test-sky/45 bg-plex-panel p-4 max-[639px]:px-3"
      aria-labelledby="runs-day-prompt-tests-heading"
      data-testid="runs-day-prompt-tests"
    >
      <h3 id="runs-day-prompt-tests-heading" className="flex items-center gap-2 text-[14px] font-semibold text-plex-text">
        <i className={`size-2.5 shrink-0 rounded-xs ${TEST_SWATCH.SKY}`} aria-hidden="true" />
        Prompt test runs
      </h3>
      <ul className="mt-2">
        {runs.map((row, i) => <PromptTestRow key={`${row.startedAt}-${row.suite}-${row.attempt}-${i}`} row={row} index={i} />)}
      </ul>
      <p className="mt-2 font-mono text-[10.5px] text-plex-text-muted" data-testid="runs-day-prompt-tests-note">
        Priced by the server with the same CostCalculator rates as the app, from the token counts the run reported.
      </p>
    </section>
  );
}

PromptTestRuns.propTypes = { runs: PropTypes.arrayOf(PropTypes.object) };
