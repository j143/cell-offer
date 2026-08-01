const endpointGrid = document.getElementById('endpoint-grid');
const endpointFilter = document.getElementById('endpoint-filter');
const endpointCount = document.getElementById('endpoint-count');
const endpointEmpty = document.getElementById('endpoint-empty');

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

async function boot() {
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
}

boot();