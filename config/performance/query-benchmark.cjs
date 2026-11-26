const { createHash } = require('node:crypto');
const { readFile, writeFile } = require('node:fs/promises');
const { parseArgs } = require('node:util');

function canonical(value) {
    if (typeof value === 'number' && (!Number.isFinite(value)
        || (Number.isInteger(value) && !Number.isSafeInteger(value)))) {
        throw new Error('Results contain a number that cannot be represented exactly');
    }
    if (Array.isArray(value)) return value.map(canonical);
    if (value && typeof value === 'object') {
        return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
    }
    return value;
}

function hash(value) {
    return createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
}

function fingerprint(rows, ordered) {
    const values = rows.map(row => JSON.stringify(canonical(row)));
    return hash(ordered ? values : values.sort());
}

function summarize(samples) {
    if (!samples.length || samples.some(value => !Number.isFinite(value) || value < 0)) {
        throw new Error('Expected nonempty finite timing samples');
    }
    const values = [...samples].sort((a, b) => a - b);
    const middle = Math.floor(values.length / 2);
    return { count: values.length, min: values[0],
        median: values.length % 2 ? values[middle] : (values[middle - 1] + values[middle]) / 2,
        max: values.at(-1), p95: values.length < 20 ? null : values[Math.ceil(values.length * 0.95) - 1] };
}

function boundedInteger(value, min, max) {
    return Number.isSafeInteger(value) && value >= min && value <= max;
}

function validateWorkload(workload) {
    if (workload?.schemaVersion !== 1 || !workload.name || !workload.model || !workload.dataset
        || !Array.isArray(workload.queries) || !boundedInteger(workload.queries.length, 1, 100)) {
        throw new Error('Invalid workload metadata or query count');
    }
    const ids = new Set();
    for (const item of workload.queries) {
        if (!/^[a-z0-9][a-z0-9_-]{0,79}$/.test(item.id) || ids.has(item.id)
            || typeof item.query !== 'string' || !boundedInteger(item.query.length, 1, 100000)
            || !boundedInteger(item.start, 0, 10000000) || !boundedInteger(item.size, 1, 10000)
            || typeof item.ordered !== 'boolean'
            || (item.expectedRows !== undefined && (!Array.isArray(item.expectedRows)
                || item.expectedRows.length > item.size || item.expectedRows.some(row => !Array.isArray(row))))) {
            throw new Error('Invalid workload query, bounds, expectation or duplicate ID');
        }
        ids.add(item.id);
    }
}

function serviceUrl(value) {
    let url;
    try { url = new URL(value); } catch { throw new Error('Invalid service URL'); }
    const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
    if (url.username || url.password || url.search || url.hash
        || !(url.protocol === 'https:' || (url.protocol === 'http:' && loopback))) {
        throw new Error('Service must use HTTPS or loopback HTTP without credentials or query parameters');
    }
    if (!url.pathname.endsWith('/')) url.pathname += '/';
    return url;
}

async function request(url, options, { timeoutMs, maxBytes, token }) {
    const start = performance.now();
    const headers = { Accept: 'application/json' };
    if (token) headers.Authorization = `Token ${token}`;
    let response;
    let bytes;
    let headersMs;
    try {
        response = await fetch(url, { ...options, headers, redirect: 'manual', signal: AbortSignal.timeout(timeoutMs) });
        headersMs = performance.now() - start;
        if (response.status !== 200) {
            await response.body?.cancel();
            throw new Error('HTTP status');
        }
        const chunks = [];
        let size = 0;
        for await (const chunk of response.body) {
            size += chunk.length;
            if (size > maxBytes) throw new Error('Response exceeds byte limit');
            chunks.push(chunk);
        }
        bytes = Buffer.concat(chunks);
    } catch {
        throw new Error(`Request failed (HTTP ${response?.status ?? 'unavailable'}; check timeout and byte limit)`);
    }
    const received = performance.now();
    return { text: bytes.toString('utf8'), sample: { headersMs, transferMs: received - start - headersMs,
        bytes: bytes.length, totalMs: received - start, parseMs: 0 } };
}

function parseJson(text) {
    try { return JSON.parse(text); } catch { throw new Error('Response is not valid JSON'); }
}

async function metadata(service, options) {
    const modelResponse = await request(new URL('model?format=json', service), {}, options);
    const model = parseJson(modelResponse.text).model;
    if (!model?.name || !model.classes || !Object.keys(model.classes).length) {
        throw new Error('Mine returned no usable model');
    }
    const version = await request(new URL('version', service), {}, options);
    const apiVersion = Number(parseJson(version.text).version);
    if (!boundedInteger(apiVersion, 1, 100000)) throw new Error('Mine returned no usable API version');
    return { modelName: model.name, modelSha256: hash(model), apiVersion };
}

async function sampleQuery(service, item, options) {
    const response = await request(new URL('query/results', service), {
        method: 'POST', body: new URLSearchParams({ query: item.query, format: 'json',
            start: String(item.start), size: String(item.size) }),
    }, options);
    const start = performance.now();
    const body = parseJson(response.text);
    if (body.wasSuccessful !== true || !Array.isArray(body.results)
        || body.results.some(row => !Array.isArray(row)) || body.results.length > item.size) {
        throw new Error('Query did not return a successful bounded row array');
    }
    const parseMs = performance.now() - start;
    return { rowCount: body.results.length, sha256: fingerprint(body.results, item.ordered),
        sample: { ...response.sample, parseMs, totalMs: response.sample.totalMs + parseMs } };
}

async function measureQuery(service, item, options, baseline) {
    const initial = await sampleQuery(service, item, options);
    const expectedChecked = item.expectedRows !== undefined;
    if (expectedChecked && initial.sha256 !== fingerprint(item.expectedRows, item.ordered)) {
        throw new Error(`${item.id}: results differ from fixture expectation`);
    }
    if (baseline && (baseline.sha256 !== initial.sha256 || baseline.rowCount !== initial.rowCount)) {
        throw new Error(`${item.id}: results differ from baseline`);
    }
    const samples = [];
    for (let index = 0; index < options.repeat; index++) {
        const observation = await sampleQuery(service, item, options);
        if (observation.sha256 !== initial.sha256 || observation.rowCount !== initial.rowCount) {
            throw new Error(`${item.id}: results changed between observations`);
        }
        samples.push(observation.sample);
    }
    return { id: item.id, querySha256: hash(item.query), start: item.start, size: item.size,
        ordered: item.ordered, expectedChecked, rowCount: initial.rowCount, sha256: initial.sha256,
        initial: initial.sample, samples, repeatedMs: summarize(samples.map(sample => sample.totalMs)) };
}

async function runBenchmark({ service, workload, baseline, repeat = 10, timeoutMs = 30000,
    maxBytes = 16777216, token }) {
    validateWorkload(workload);
    const root = serviceUrl(service);
    if (!boundedInteger(repeat, 1, 1000) || !boundedInteger(timeoutMs, 1, 300000)
        || !boundedInteger(maxBytes, 1, 67108864)) throw new Error('Invalid benchmark limits');
    const workloadSha256 = hash(workload);
    if (baseline && (baseline.schemaVersion !== 1 || baseline.workloadSha256 !== workloadSha256
        || baseline.queries?.length !== workload.queries.length)) throw new Error('Baseline workload differs');
    const options = { repeat, timeoutMs, maxBytes, token };
    const info = await metadata(root, options);
    if (info.modelName !== workload.model) throw new Error('Workload model differs from service');
    if (baseline && info.modelSha256 !== baseline.mine?.modelSha256) throw new Error('Baseline model differs');
    const queries = [];
    for (const item of workload.queries) {
        const prior = baseline?.queries.find(entry => entry.id === item.id);
        if (baseline && !prior) throw new Error('Baseline query missing');
        queries.push(await measureQuery(root, item, options, prior));
    }
    return { schemaVersion: 1, measuredAt: new Date().toISOString(), service: root.href,
        workloadSha256, workloadName: workload.name, dataset: workload.dataset, mine: info,
        repeat, timeoutMs, maxBytes, comparedToBaseline: Boolean(baseline),
        cacheState: 'uncontrolled; initial observation is not a cold-cache measurement', queries };
}

async function main() {
    const { values } = parseArgs({ options: {
        service: { type: 'string' }, workload: { type: 'string' }, output: { type: 'string' },
        baseline: { type: 'string' }, repeat: { type: 'string', default: '10' },
        'timeout-ms': { type: 'string', default: '30000' }, 'max-bytes': { type: 'string', default: '16777216' },
    } });
    if (!values.service || !values.workload || !values.output) {
        throw new Error('Required: --service URL --workload FILE --output FILE');
    }
    const workload = parseJson(await readFile(values.workload, 'utf8'));
    const baseline = values.baseline ? parseJson(await readFile(values.baseline, 'utf8')) : undefined;
    const report = await runBenchmark({ service: values.service, workload, baseline,
        repeat: Number(values.repeat), timeoutMs: Number(values['timeout-ms']),
        maxBytes: Number(values['max-bytes']), token: process.env.INTERMINE_BENCHMARK_TOKEN });
    await writeFile(values.output, JSON.stringify(report, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    console.log(`Recorded ${report.queries.length} queries, ${report.repeat} repeated samples each`);
}

module.exports = { fingerprint, summarize, runBenchmark };
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
