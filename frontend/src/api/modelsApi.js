import axios from 'axios';

/**
 * Fetch available evaluation models and the currently active one.
 *
 * @returns {Promise<{available: string[], active: string}>} available models and active model
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
 * Set the active evaluation model (admin only).
 *
 * @param {string} model - model name (HAIKU or SONNET)
 * @returns {Promise<{active: string}>} the newly activated model
 */
export async function setActiveModel(model) {
  try {
    const response = await axios.put('/api/models/active', { model });
    return response.data;
  } catch (error) {
    console.error('Failed to set active model:', error);
    throw error;
  }
}
