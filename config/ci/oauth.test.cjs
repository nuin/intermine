const assert = require('node:assert/strict');
const { test } = require('node:test');

assert.ok(process.env.TESTMODEL_URL, 'TESTMODEL_URL must identify the disposable test mine');
const root = process.env.TESTMODEL_URL.replace(/\/$/, '') + '/service/';

test('OAuth authorization builds a provider link from the deployed runtime', async () => {
    const response = await fetch(new URL('oauth2authenticator?provider=GITHUB', root), {
        signal: AbortSignal.timeout(15000),
    });
    const text = await response.text();
    assert.ok(response.ok, `OAuth authorization: ${response.status} ${text}`);
    const result = JSON.parse(text);
    const link = new URL(result.link);
    assert.equal(link.protocol, 'https:');
    assert.equal(link.hostname, 'github.com');
    assert.equal(link.searchParams.get('response_type'), 'code');
});

test('OAuth callback reports an invalid exchange as a JSON error', async () => {
    const url = new URL('oauth2callback', root);
    url.search = new URLSearchParams({
        provider: 'UNCONFIGURED_TEST_PROVIDER',
        code: 'invalid-code-sentinel',
        redirect_uri: 'https://client.example.test/callback',
        format: 'json',
    });
    const response = await fetch(url, { signal: AbortSignal.timeout(15000) });
    const text = await response.text();
    assert.equal(response.status, 400, `Invalid exchange: ${response.status} ${text}`);
    assert.ok(JSON.parse(text).error);
    assert.ok(!text.includes('invalid-code-sentinel'));
});

async function transaction(operation, parameters) {
    const response = await fetch(new URL(`oauth2/transactions/${operation}`, root), {
        method: 'POST',
        body: new URLSearchParams(parameters),
        signal: AbortSignal.timeout(15000),
    });
    assert.equal(response.headers.get('cache-control'), 'no-store');
    return { status: response.status, body: await response.json() };
}

test('deployed OAuth transactions bind callback parameters and cancel without exchange', async () => {
    const callback = 'https://client.example.test/bluegenes/callback?provider=GITHUB';
    const state = 'browser-state-sentinel-abcdefghijklmnopqrstuvwxyz';
    const prepared = await transaction('prepare', {
        provider: 'GITHUB', redirect_uri: callback, state,
    });
    assert.equal(prepared.status, 200);
    const { handle, authorizationUrl } = prepared.body.output;
    assert.match(handle, /^[A-Za-z0-9_-]{43}$/);
    try {
        const link = new URL(authorizationUrl);
        assert.equal(link.hostname, 'github.com');
        assert.deepEqual(link.searchParams.getAll('state'), [state]);
        assert.deepEqual(link.searchParams.getAll('redirect_uri'), [callback]);
        assert.ok(!authorizationUrl.includes(handle));
        assert.equal((await transaction('complete', { handle })).status, 409);
    } finally {
        assert.equal((await transaction('cancel', { handle })).status, 200);
    }
    assert.equal((await transaction('complete', { handle })).status, 409);
});

test('deployed transaction servlet rejects duplicate fields and GET operations', async () => {
    const duplicate = await transaction('cancel', [['handle', 'first'], ['handle', 'second']]);
    assert.equal(duplicate.status, 400);
    const response = await fetch(new URL('oauth2/transactions/prepare', root), {
        signal: AbortSignal.timeout(15000),
    });
    assert.equal(response.status, 405);
});

test('deployed token-specific cleanup tolerates an already-invalid credential', async () => {
    const token = 'invalid-cleanup-token-sentinel';
    const result = await transaction('revoke', { token });
    assert.equal(result.status, 200);
    assert.equal(result.body.output.success, true);
    assert.ok(!JSON.stringify(result.body).includes(token));
});
