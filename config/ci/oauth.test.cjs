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
