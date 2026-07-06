import { env } from '$env/dynamic/public';

const API = (env.PUBLIC_API_BASE || 'http://localhost:8080').replace(/\/$/, '');

export const runStore = $state({
  runs: [],
  nodeRunIdsByNodeId: {},
  loading: false,
  nodeLoadingByNodeId: {},
  error: '',
  nodeErrorsByNodeId: {},
  updatedAt: 0
});

export async function fetchRuns() {
  runStore.loading = true;
  runStore.error = '';

  try {
    const response = await fetch(`${API}/api/v1/runs`);
    if (!response.ok) throw new Error(`/api/v1/runs -> ${response.status}`);
    runStore.runs = await response.json();
    runStore.updatedAt = Date.now();
  } catch (error) {
    runStore.error = error.message;
    console.warn('[nodemetry] runs fetch failed:', error.message);
  } finally {
    runStore.loading = false;
  }
}

export async function fetchNodeRunIds(nodeId) {
  if (!nodeId) return [];

  runStore.nodeLoadingByNodeId = {
    ...runStore.nodeLoadingByNodeId,
    [nodeId]: true
  };
  runStore.nodeErrorsByNodeId = {
    ...runStore.nodeErrorsByNodeId,
    [nodeId]: ''
  };

  try {
    const encodedNodeId = encodeURIComponent(nodeId);
    const response = await fetch(`${API}/api/v1/nodes/${encodedNodeId}/runs`);
    if (!response.ok) throw new Error(`/api/v1/nodes/${nodeId}/runs -> ${response.status}`);

    const runIds = await response.json();
    runStore.nodeRunIdsByNodeId = {
      ...runStore.nodeRunIdsByNodeId,
      [nodeId]: runIds
    };
    return runIds;
  } catch (error) {
    runStore.nodeErrorsByNodeId = {
      ...runStore.nodeErrorsByNodeId,
      [nodeId]: error.message
    };
    console.warn(`[nodemetry] node runs fetch failed for ${nodeId}:`, error.message);
    return [];
  } finally {
    runStore.nodeLoadingByNodeId = {
      ...runStore.nodeLoadingByNodeId,
      [nodeId]: false
    };
  }
}

export function shortRunId(runId) {
  return runId ? runId.slice(0, 8) : 'none';
}
