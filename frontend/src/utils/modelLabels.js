/**
 * Display labels for the backend's EvaluationModel enum names.
 *
 * The one place a model name becomes words, so a new model is added here and nowhere else. It is
 * a static table, never a network read: the labels must render even when the models endpoint has
 * not answered (a job-run row, a popup footer). Extended-thinking variants keep the "(ET)" wording
 * the briefing model test view already used.
 */
const MODEL_LABELS = {
  HAIKU: 'Haiku',
  SONNET: 'Sonnet 4.6',
  SONNET_ET: 'Sonnet 4.6 (ET)',
  SONNET_55: 'Sonnet 5.5',
  OPUS: 'Opus',
  OPUS_ET: 'Opus (ET)',
};

/**
 * Maps an EvaluationModel enum name to its display label.
 *
 * @param {string|null|undefined} model - enum name such as `SONNET_55`
 * @returns {string} the label, or the raw name for an unknown model, or '' for none
 */
export function modelLabel(model) {
  if (!model) return '';
  return Object.prototype.hasOwnProperty.call(MODEL_LABELS, model) ? MODEL_LABELS[model] : model;
}
