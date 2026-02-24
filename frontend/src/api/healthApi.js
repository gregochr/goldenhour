import axios from 'axios';

const healthAxios = axios.create({
  baseURL: 'http://127.0.0.1:8082',
});

/**
 * Polls backend health status.
 * @returns {Promise<string>} "UP" or "DOWN"
 */
export async function getHealth() {
  try {
    const response = await healthAxios.get('/actuator/health');
    return response.data.status;
  } catch {
    return 'DOWN';
  }
}
