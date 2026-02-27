import axios from 'axios';

/**
 * Polls backend health status
 * @returns {Promise<string>} "UP" or "DOWN"
 */
export async function getHealth() {
  try {
    // In Docker/prod: backend is proxied, use relative path
    // In dev: backend is on 8082, use explicit URL
    const backendUrl = import.meta.env.DEV
      ? 'http://localhost:8082/actuator/health'
      : '/actuator/health';

    const response = await axios.get(backendUrl, { timeout: 5000 });
    console.log('[Health] Status:', response.data.status);
    return response.data.status;
  } catch (error) {
    console.error('[Health] Error:', error.message, error.response?.status);
    return 'DOWN';
  }
}
