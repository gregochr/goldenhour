import axios from 'axios';

export const runPromptTest = (model, runType) =>
  axios.post(`/api/prompt-test/run?model=${model}&runType=${runType}`);

export const replayPromptTest = (parentRunId) =>
  axios.post(`/api/prompt-test/replay?parentRunId=${parentRunId}`);

export const getPromptTestRuns = () =>
  axios.get('/api/prompt-test/runs');

export const getPromptTestResults = (testRunId) =>
  axios.get(`/api/prompt-test/results?testRunId=${testRunId}`);

export const getGitInfo = () =>
  axios.get('/api/prompt-test/git-info');
