const endpointGrid = document.getElementById('endpoint-grid');
const endpointFilter = document.getElementById('endpoint-filter');
const endpointCount = document.getElementById('endpoint-count');
const endpointEmpty = document.getElementById('endpoint-empty');
const apiBaseInput = document.getElementById('api-base-url');
const saveApiBaseButton = document.getElementById('save-api-base');
const refreshExperimentsButton = document.getElementById('refresh-experiments');
const experimentGrid = document.getElementById('experiment-grid');
const experimentEmpty = document.getElementById('experiment-empty');
const probeForm = document.getElementById('probe-form');
const probeResult = document.getElementById('probe-result');
const runLoadButton = document.getElementById('run-load');
const loadProgress = document.getElementById('load-progress');
const loadResult = document.getElementById('load-result');
const loadForm = document.getElementById('load-form');

const storageKey = 'cell-offer-api-base-url';

const escapeHtml = (value) => String(value)
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#39;');

const methodOrder = ['get', 'post', 'put', 'patch', 'delete', 'options', 'head'];

function describeOperation(operation) {
  return operation.summary || operation.description || 'No description provided.';
}

function normalizeOperations(paths) {
  return Object.entries(paths).flatMap(([path, methods]) =>
    methodOrder.flatMap((method) => {
      const operation = methods[method];
      if (!operation) {
        return [];
      }

      return [{
        path,
        method,
        summary: operation.summary || operation.operationId || `${method.toUpperCase()} ${path}`,
        description: describeOperation(operation),
        tags: operation.tags || [],
        searchText: [path, method, operation.summary, operation.description, ...(operation.tags || [])]
          .filter(Boolean)
          .join(' ')
          .toLowerCase(),
      }];
    })
  );
}

function getApiBase() {
  return (apiBaseInput.value || 'http://localhost:8080').replace(/\/$/, '');
}

function apiUrl(path) {
  return `${getApiBase()}${path}`;
}

function readField(form, name) {
  return form.elements.namedItem(name)?.value ?? '';
}

function formatJson(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

async function fetchJson(path, options = {}) {
  const response = await fetch(apiUrl(path), {
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {}),
    },
    ...options,
  });

  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }

  return response.json();
}

function renderOperations(operations, filterText = '') {
  const filtered = operations.filter((operation) => operation.searchText.includes(filterText.toLowerCase()));
  endpointCount.textContent = String(operations.length);
  endpointGrid.innerHTML = filtered.map((operation) => `
    <article class="endpoint-card">
      <div class="endpoint-top">
        <span class="method ${operation.method}">${operation.method.toUpperCase()}</span>
        <span class="tag">${escapeHtml(operation.tags[0] || 'cell-offer')}</span>
      </div>
      <p class="endpoint-path">${escapeHtml(operation.path)}</p>
      <h3>${escapeHtml(operation.summary)}</h3>
      <p class="endpoint-desc">${escapeHtml(operation.description)}</p>
      <div class="endpoint-tags">
        ${operation.tags.map((tag) => `<span class="tag">${escapeHtml(tag)}</span>`).join('')}
      </div>
    </article>
  `).join('');

  endpointEmpty.classList.toggle('hidden', filtered.length !== 0);
}

function renderExperiments(experiments) {
  experimentGrid.innerHTML = experiments.map((experiment) => `
    <article class="experiment-card">
      <div class="endpoint-top">
        <span class="status-pill ${experiment.enabled ? 'status-on' : 'status-off'}">${experiment.enabled ? 'enabled' : 'disabled'}</span>
        <span class="tag">${escapeHtml(experiment.unitType)}</span>
      </div>
      <h3>${escapeHtml(experiment.name)}</h3>
      <p class="endpoint-desc">Controls: ${escapeHtml((experiment.parameterKeys || []).join(', ') || 'none')}</p>
    </article>
  `).join('');

  experimentEmpty.classList.toggle('hidden', experiments.length !== 0);
}

async function loadExperiments() {
  experimentGrid.innerHTML = '';
  experimentEmpty.classList.remove('hidden');

  try {
    const payload = await fetchJson('/citrus/experiments');
    renderExperiments(payload.experiments || []);
  } catch (error) {
    experimentGrid.innerHTML = `
      <article class="endpoint-card">
        <div class="endpoint-top">
          <span class="method get">GET</span>
          <span class="tag">probe</span>
        </div>
        <p class="endpoint-path">/citrus/experiments</p>
        <h3>Experiment registry unavailable</h3>
        <p class="endpoint-desc">${escapeHtml(error.message)}. Check the API base URL and whether the backend is running.</p>
      </article>
    `;
    experimentEmpty.classList.add('hidden');
  }
}

async function runProbe(event) {
  event.preventDefault();
  const form = event.currentTarget;

  try {
    const params = new URLSearchParams({
      paramKey: readField(form, 'paramKey'),
      unitType: readField(form, 'unitType'),
      unitId: readField(form, 'unitId'),
      kind: readField(form, 'kind'),
      defaultValue: readField(form, 'defaultValue'),
    });

    const result = await fetchJson(`/citrus/probe?${params.toString()}`);
    probeResult.textContent = formatJson(result);
  } catch (error) {
    probeResult.textContent = formatJson({ error: error.message });
  }
}

async function runLoad() {
  const form = loadForm;
  const cellId = readField(form, 'cellId');
  const requestCount = Number(readField(form, 'requestCount'));
  const batchSize = Number(readField(form, 'batchSize'));
  const priority = Number(readField(form, 'priority'));
  const ttlMillis = Number(readField(form, 'ttlMillis'));
  const riderPrefix = readField(form, 'riderPrefix') || 'r';

  loadProgress.textContent = `Sending ${requestCount} offer(s) to ${cellId}...`;
  const startedAt = performance.now();
  let sent = 0;
  let failures = 0;

  for (let offset = 0; offset < requestCount; offset += batchSize) {
    const batch = [];
    const upperBound = Math.min(offset + batchSize, requestCount);

    for (let i = offset; i < upperBound; i += 1) {
      batch.push(
        fetch(apiUrl(`/cells/${encodeURIComponent(cellId)}/offers`), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            driverId: `driver-${i}`,
            riderId: `${riderPrefix}-${Math.floor(Math.random() * 1000)}`,
            priority,
            ttlMillis,
          }),
        }).then((response) => {
          if (!response.ok) {
            throw new Error(`${response.status} ${response.statusText}`);
          }
          sent += 1;
        }).catch(() => {
          failures += 1;
        })
      );
    }

    await Promise.all(batch);
    loadProgress.textContent = `Sent ${sent}/${requestCount} request(s)...`;
  }

  const stats = await fetchJson(`/cells/${encodeURIComponent(cellId)}/stats`);
  const elapsedMs = Math.round(performance.now() - startedAt);
  loadProgress.textContent = `Completed in ${elapsedMs} ms.`;
  loadResult.textContent = formatJson({ sent, failures, elapsedMs, stats });
}

async function boot() {
  const storedBase = window.localStorage.getItem(storageKey);
  if (storedBase) {
    apiBaseInput.value = storedBase;
  }

  saveApiBaseButton.addEventListener('click', () => {
    window.localStorage.setItem(storageKey, getApiBase());
    loadExperiments();
  });

  refreshExperimentsButton.addEventListener('click', loadExperiments);
  probeForm.addEventListener('submit', runProbe);
  runLoadButton.addEventListener('click', runLoad);
  loadForm.addEventListener('submit', (event) => {
    event.preventDefault();
    runLoad();
  });

  try {
    const response = await fetch('./openapi-v1.json', { cache: 'no-store' });
    if (!response.ok) {
      throw new Error(`OpenAPI request failed with ${response.status}`);
    }

    const spec = await response.json();
    const operations = normalizeOperations(spec.paths || {});
    renderOperations(operations);

    endpointFilter.addEventListener('input', () => {
      renderOperations(operations, endpointFilter.value);
    });
  } catch (error) {
    endpointGrid.innerHTML = `
      <article class="endpoint-card">
        <div class="endpoint-top">
          <span class="method get">GET</span>
          <span class="tag">offline</span>
        </div>
        <p class="endpoint-path">/v3/api-docs/v1</p>
        <h3>OpenAPI spec unavailable</h3>
        <p class="endpoint-desc">${escapeHtml(error.message)}. The static page still loads, but the live endpoint catalog could not be fetched.</p>
      </article>
    `;
    endpointEmpty.classList.add('hidden');
    endpointCount.textContent = '0';
  }

  await loadExperiments();
}

boot();