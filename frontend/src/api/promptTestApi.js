import apiClient from './axiosClient.js';

export const runPromptTest = (model, runType) =>
  apiClient.post(`/api/prompt-test/run?model=${model}&runType=${runType}`);

export const replayPromptTest = (parentRunId) =>
  apiClient.post(`/api/prompt-test/replay?parentRunId=${parentRunId}`);

export const getPromptTestRun = (runId) =>
  apiClient.get(`/api/prompt-test/runs/${runId}`);

export const getPromptTestRuns = () =>
  apiClient.get('/api/prompt-test/runs');

export const getPromptTestResults = (testRunId) =>
  apiClient.get(`/api/prompt-test/results?testRunId=${testRunId}`);

export const getGitInfo = () =>
  apiClient.get('/api/prompt-test/git-info');
