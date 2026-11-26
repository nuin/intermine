const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { test } = require('node:test');
const { spawnSync } = require('node:child_process');
const { mkdtempSync, writeFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { fingerprint, summarize, runBenchmark } = require('./query-benchmark.cjs');

const rows = [[1, 'β'], [2, null], [2, null]];
const workload = () => ({
    schemaVersion: 1, name: 'fixture', model: 'testmodel', dataset: 'seeded-test-data',
    queries: [{ id: 'employees', query: '<query model="testmodel" view="Employee.id Employee.name"/>',
        start: 0, size: 10, ordered: true, expectedRows: rows }],
});

async function fixture(t, queryHandler) {
    let calls = 0;
    const requests = [];
    const server = createServer(async (request, response) => {
        const chunks = [];
        for await (const chunk of request) chunks.push(chunk);
        requests.push({ path: request.url, headers: request.headers,
            body: Buffer.concat(chunks).toString(), method: request.method });
        response.setHeader('content-type', 'application/json');
        const path = new URL(request.url, 'http://fixture').pathname;
        if (path === '/service/model') {
            response.end(JSON.stringify({ model: { name: 'testmodel', classes: { Employee: {} } } }));
        } else if (path === '/service/version') {
            response.end(JSON.stringify({ version: '36', wasSuccessful: true }));
        } else {
            calls++;
            queryHandler(response, calls);
        }
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    t.after(() => { server.closeAllConnections(); server.close(); });
    return { service: `http://127.0.0.1:${server.address().port}/service/`, requests };
}

function envelope(values = rows) {
    return { wasSuccessful: true, error: null, statusCode: 200, modelName: 'testmodel',
        start: '0', views: ['Employee.id', 'Employee.name'], results: values };
}

function success(response, values = rows) {
    response.end(JSON.stringify(envelope(values)));
}

test('successful-looking malformed pages fail without independent row expectations', async t => {
    const changes = [body => { body.start = 100; }, body => { body.modelName = 'wrong'; },
        body => { body.results = [[1]]; }, body => { body.views = []; },
        body => { body.views = ['Employee.id', null]; }, body => { body.error = 'private-error'; },
        body => { body.statusCode = 500; }];
    for (const [index, change] of changes.entries()) {
        await t.test(String(index), async child => {
            const body = envelope();
            change(body);
            const { service } = await fixture(child, response => response.end(JSON.stringify(body)));
            const input = workload();
            delete input.queries[0].expectedRows;
            await assert.rejects(runBenchmark({ service, workload: input, repeat: 1 }), error => {
                assert.ok(!error.message.includes('private-error'));
                return true;
            });
        });
    }
});

test('numeric precision loss and malformed UTF-8 cannot become stable results', async t => {
    for (const invalid of ['1.00000000000000001', '9007199254740993', '1e-999', '"BADBYTE"']) {
        await t.test(invalid, async child => {
            const { service } = await fixture(child, (response, call) => {
                let body = JSON.stringify(envelope([['NUMBER', null]])).replace('"NUMBER"',
                    call === 1 ? invalid : invalid.replace(/1$/, '2'));
                if (invalid.includes('BADBYTE')) body = Buffer.from(body.replace('BADBYTE', '\u0080'), 'latin1');
                response.end(body);
            });
            const input = workload();
            delete input.queries[0].expectedRows;
            await assert.rejects(runBenchmark({ service, workload: input, repeat: 1 }));
        });
    }
});

test('equivalent numeric spellings retain precision and compare successfully', async t => {
    const { service } = await fixture(t, (response, call) => {
        response.end(JSON.stringify(envelope([['NUMBER', null]])).replace('"NUMBER"', call === 1 ? '1.2500e2' : '125'));
    });
    const input = workload();
    input.queries[0].expectedRows = [[125, null]];
    assert.equal((await runBenchmark({ service, workload: input, repeat: 1 })).queries[0].rowCount, 1);
});

test('file-based expectations reject decimal rounding before making requests', t => {
    const directory = mkdtempSync(join(tmpdir(), 'intermine-precision-'));
    t.after(() => rmSync(directory, { recursive: true }));
    const file = join(directory, 'workload.json');
    writeFileSync(file, JSON.stringify(workload()).replace('[[1,', '[[1.00000000000000001,'));
    const result = spawnSync(process.execPath, [join(__dirname, 'query-benchmark.cjs'),
        '--service', 'http://127.0.0.1:1/service/', '--workload', file, '--output', join(directory, 'out.json')], { encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /precision/);
    assert.ok(!result.stderr.includes('1.00000000000000001'));
});

test('fingerprints retain types, duplicate rows and explicit ordering', () => {
    assert.notEqual(fingerprint(rows, true), fingerprint([...rows].reverse(), true));
    assert.equal(fingerprint(rows, false), fingerprint([...rows].reverse(), false));
    assert.notEqual(fingerprint(rows, false), fingerprint(rows.slice(0, 2), false));
    assert.notEqual(fingerprint([[1]], true), fingerprint([['1']], true));
    assert.equal(fingerprint([[{ a: 1, b: 2 }]], true), fingerprint([[{ b: 2, a: 1 }]], true));
});

test('summary separates median from unsupported small-sample p95', () => {
    assert.deepEqual(summarize([1, 3, 2, 4]), { count: 4, min: 1, median: 2.5, max: 4, p95: null });
    assert.equal(summarize(Array.from({ length: 20 }, (_, i) => i + 1)).p95, 19);
    assert.throws(() => summarize([]));
});

test('real HTTP replay checks every sample and keeps secrets and data out of reports', async t => {
    const { service, requests } = await fixture(t, response => success(response));
    const report = await runBenchmark({ service, workload: workload(), repeat: 2, token: 'secret-token' });
    const entry = report.queries[0];
    assert.equal(entry.rowCount, 3);
    assert.equal(entry.expectedChecked, true);
    assert.equal(entry.samples.length, 2);
    assert.ok(entry.initial.totalMs >= 0);
    assert.ok(entry.samples.every(sample => sample.bytes > 0 && sample.totalMs >= sample.parseMs));
    assert.equal(requests.filter(request => request.method === 'POST').length, 3);
    assert.ok(requests.every(request => request.headers.authorization === 'Token secret-token'));
    const encoded = JSON.stringify(report);
    assert.ok(!encoded.includes('secret-token'));
    assert.ok(!encoded.includes('<query'));
    assert.ok(!encoded.includes('β'));
    assert.equal(requests.at(-1).path, '/service/query/results');
    assert.equal(new URLSearchParams(requests.at(-1).body).get('size'), '10');
    const second = await runBenchmark({ service, workload: workload(), repeat: 1, baseline: report });
    assert.equal(second.comparedToBaseline, true);
});

test('changed values, ordering and lost duplicates fail even after a good initial sample', async t => {
    for (const changed of [[[1, 'wrong'], [2, null], [2, null]], [...rows].reverse(), rows.slice(0, 2)]) {
        await t.test(JSON.stringify(changed), async child => {
            const { service } = await fixture(child, (response, call) => success(response, call === 1 ? rows : changed));
            await assert.rejects(runBenchmark({ service, workload: workload(), repeat: 1 }), /results changed|expectation/i);
        });
    }
});

test('an initial consistency reference cannot pretend to be an independent expectation', async t => {
    const { service } = await fixture(t, response => success(response));
    const input = workload();
    delete input.queries[0].expectedRows;
    const report = await runBenchmark({ service, workload: input, repeat: 1 });
    assert.equal(report.queries[0].expectedChecked, false);
    const altered = structuredClone(report);
    altered.queries[0].sha256 = '0'.repeat(64);
    await assert.rejects(runBenchmark({ service, workload: input, repeat: 1, baseline: altered }), /baseline/i);
    input.queries[0].size = 9;
    await assert.rejects(runBenchmark({ service, workload: input, repeat: 1, baseline: report }), /workload/i);
});

test('failed, malformed, redirected and oversized responses never become timing successes', async t => {
    const cases = [
        response => { response.statusCode = 500; response.end('private database error secret-token'); },
        response => response.end('{'),
        response => response.end(JSON.stringify({ wasSuccessful: false, results: rows })),
        response => response.end(JSON.stringify({ wasSuccessful: true, results: 'not rows' })),
        response => { response.writeHead(302, { location: 'https://example.org/leak' }); response.end(); },
        response => success(response, [['x'.repeat(1024)]]),
    ];
    for (const [index, handler] of cases.entries()) {
        await t.test(String(index), async child => {
            const { service } = await fixture(child, handler);
            await assert.rejects(runBenchmark({ service, workload: workload(), repeat: 1, maxBytes: 512, token: 'secret-token' }), error => {
                assert.ok(!error.message.includes('secret-token'));
                assert.ok(!error.message.includes('private database'));
                return true;
            });
        });
    }
});

test('a stalled body is bounded by the request timeout', async t => {
    const { service } = await fixture(t, response => { response.writeHead(200); response.write('{'); });
    await assert.rejects(runBenchmark({ service, workload: workload(), repeat: 1, timeoutMs: 50 }), /timeout|failed/i);
});

test('invalid workload limits and unsafe service URLs fail before network access', async () => {
    for (const service of ['http://example.org/service/', 'http://user:password@localhost/service/', 'https://example.org/service/?token=x']) {
        await assert.rejects(runBenchmark({ service, workload: workload(), repeat: 1 }));
    }
    for (const mutate of [w => { w.queries = []; }, w => { w.queries[0].size = 0; },
        w => { w.queries.push(w.queries[0]); }, w => { delete w.queries[0].ordered; }]) {
        const input = workload();
        mutate(input);
        await assert.rejects(runBenchmark({ service: 'http://127.0.0.1:1/service/', workload: input, repeat: 1 }), /workload/i);
    }
});
