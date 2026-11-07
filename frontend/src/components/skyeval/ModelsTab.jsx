import { useState } from 'react';
import PropTypes from 'prop-types';
import AccuracyChart from './AccuracyChart.jsx';
import AllModelsTable from './AllModelsTable.jsx';
import { LaneCard, VerdictCard } from './LaneCards.jsx';
import VersionCompare from './VersionCompare.jsx';

/**
 * The Models tab: verdict card, the two production lane cards, accuracy over time, "Did a change make ratings
 * worse?" and the All models table. The highlighted models (a multi-select) are shared by the
 * chart's chips and the table's rows.
 *
 * @param {object} props
 * @param {object} props.overview the served overview
 * @param {Date} props.now the moment ages and the chart's right edge are measured to
 * @param {(models: string[]) => void} props.onRunCheck opens the Run a check panel prefilled with these models
 * @param {(model: string) => void} props.onSeeFixtures opens the Fixtures tab on that model
 */
export default function ModelsTab({ overview, now, onRunCheck, onSeeFixtures }) {
  const [highlighted, setHighlighted] = useState([]);
  const onToggle = (model) => setHighlighted((cur) => (
    cur.includes(model) ? cur.filter((x) => x !== model) : [...cur, model]
  ));
  const onClear = () => setHighlighted([]);
  const { head, lanes, models, sessions, versions, verdict, comparisons, fixtures } = overview;
  return (
    <div className="flex min-w-0 flex-col gap-4" data-testid="sky-eval-models-tab">
      <VerdictCard verdict={verdict} head={head} models={models} now={now} />
      <div className="grid grid-cols-2 gap-4 max-[999px]:grid-cols-[minmax(0,1fr)]" data-testid="sky-eval-lanes">
        {lanes.map((lane) => (
          <LaneCard
            key={lane.runType}
            lane={lane}
            stats={models.find((m) => m.model === lane.model)}
            models={models}
            head={head}
            fixtureCount={fixtures.length}
            now={now}
            onRunCheck={onRunCheck}
            onSeeFixtures={onSeeFixtures}
          />
        ))}
      </div>
      <AccuracyChart
        sessions={sessions}
        models={models}
        versions={versions}
        highlighted={highlighted}
        onToggle={onToggle}
        onClear={onClear}
        now={now}
      />
      <VersionCompare comparisons={comparisons} now={now} />
      <AllModelsTable
        models={models}
        lanes={lanes}
        head={head}
        highlighted={highlighted}
        onToggle={onToggle}
        now={now}
      />
    </div>
  );
}

ModelsTab.propTypes = {
  overview: PropTypes.shape({
    head: PropTypes.object,
    lanes: PropTypes.array.isRequired,
    models: PropTypes.array.isRequired,
    sessions: PropTypes.array.isRequired,
    versions: PropTypes.array.isRequired,
    verdict: PropTypes.object.isRequired,
    comparisons: PropTypes.array.isRequired,
    fixtures: PropTypes.array.isRequired,
  }).isRequired,
  now: PropTypes.instanceOf(Date).isRequired,
  onRunCheck: PropTypes.func.isRequired,
  onSeeFixtures: PropTypes.func.isRequired,
};
