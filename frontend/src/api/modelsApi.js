import axios from 'axios';

/**
 * Fetch available evaluation models, active model per run type, and optimisation strategies.
 *
 * @returns {Promise<{available: string[], configs: Object<string, string>, optimisationStrategies: Object}>}
 *   available models, per-run-type active models, and optimisation strategies
 */
export async function getAvailableModels() {
  try {
    const response = await axios.get('/api/models');
    return response.data;
  } catch (error) {
    console.error('Failed to fetch available models:', error);
    throw error;
  }
}

/**
 * Set the active evaluation model for a specific run type (admin only).
 *
 * @param {string} runType - run type (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM)
 * @param {string} model - model name (HAIKU, SONNET, or OPUS)
 * @returns {Promise<{runType: string, active: string}>} the updated config
 */
export async function setActiveModel(runType, model) {
  try {
    const response = await axios.put('/api/models/active', { runType, model });
    return response.data;
  } catch (error) {
    console.error('Failed to set active model:', error);
    throw error;
  }
}

/**
 * Enable or disable extended thinking for a specific run type (admin only).
 *
 * @param {string} runType - run type (e.g. BRIEFING_BEST_BET)
 * @param {boolean} enabled - whether to enable extended thinking
 * @returns {Promise<{runType: string, extendedThinking: boolean}>} the updated config
 */
export async function setExtendedThinking(runType, enabled) {
  try {
    const response = await axios.put('/api/models/extended-thinking', { runType, enabled });
    return response.data;
  } catch (error) {
    console.error('Failed to set extended thinking:', error);
    throw error;
  }
}

/**
 * Toggle an optimisation strategy for a specific run type (admin only).
 *
 * @param {string} runType - run type (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM)
 * @param {string} strategyType - strategy name (SKIP_LOW_RATED, SKIP_EXISTING, etc.)
 * @param {boolean} enabled - whether to enable or disable
 * @param {number|null} paramValue - optional integer parameter (e.g. min rating threshold)
 * @returns {Promise<{runType: string, strategyType: string, enabled: boolean, paramValue: *}>}
 */
export async function updateOptimisationStrategy(runType, strategyType, enabled, paramValue = null) {
  try {
    const response = await axios.put('/api/models/optimisation', {
      runType,
      strategyType,
      enabled,
      paramValue,
    });
    return response.data;
  } catch (error) {
    console.error('Failed to update optimisation strategy:', error);
    throw error;
  }
}
