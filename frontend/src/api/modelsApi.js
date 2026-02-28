import axios from 'axios';

/**
 * Fetch available evaluation models and active model per run type.
 *
 * @returns {Promise<{available: string[], configs: Object<string, string>}>}
 *   available models and per-run-type active models
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
