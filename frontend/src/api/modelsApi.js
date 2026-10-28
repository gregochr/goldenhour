import apiClient from './axiosClient.js';

/**
 * Fetch available evaluation models and the active model per run type.
 *
 * @returns {Promise<{available: string[], configs: Object<string, string>}>}
 *   available models and per-run-type active models
 */
export async function getAvailableModels() {
  try {
    const response = await apiClient.get('/api/models');
    return response.data;
  } catch (error) {
    console.error('Failed to fetch available models:', error);
    throw error;
  }
}

/**
 * Set the active evaluation model for a specific run type (admin only).
 *
 * @param {string} runType - a configurable run type (e.g. BRIEFING_BEST_BET, BATCH_NEAR_TERM)
 * @param {string} model - model name (HAIKU, HAIKU_55, SONNET, SONNET_55, OPUS)
 * @returns {Promise<{runType: string, active: string}>} the updated config
 */
export async function setActiveModel(runType, model) {
  try {
    const response = await apiClient.put('/api/models/active', { runType, model });
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
    const response = await apiClient.put('/api/models/extended-thinking', { runType, enabled });
    return response.data;
  } catch (error) {
    console.error('Failed to set extended thinking:', error);
    throw error;
  }
}
