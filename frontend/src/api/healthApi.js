import axios from 'axios';

/** Components whose failure is a soft warning (amber), not a hard failure (red). */
const SOFT_COMPONENTS = new Set(['mail']);

/**
 * Polls backend health status with component details.
 *
 * @returns {Promise<{status: string, degraded: string[]}>}
 *   status: "UP", "DOWN", or "DEGRADED"
 *   degraded: names of soft-fail components that are down (e.g. ["mail"])
 */
export async function getHealth() {
  try {
    const backendUrl = import.meta.env.DEV
      ? 'http://localhost:8082/actuator/health'
      : '/actuator/health';

    const response = await axios.get(backendUrl, { timeout: 5000 });
    const data = response.data;
    const components = data.components || {};

    // Find which soft components are down
    const degraded = Object.entries(components)
      .filter(([name, info]) => SOFT_COMPONENTS.has(name) && info.status !== 'UP')
      .map(([name]) => name);

    // Find if any hard component is down
    const hardDown = Object.entries(components)
      .some(([name, info]) => !SOFT_COMPONENTS.has(name) && info.status !== 'UP');

    if (hardDown) {
      return { status: 'DOWN', degraded: [] };
    }
    if (degraded.length > 0) {
      return { status: 'DEGRADED', degraded };
    }
    return { status: 'UP', degraded: [] };
  } catch {
    return { status: 'DOWN', degraded: [] };
  }
}
