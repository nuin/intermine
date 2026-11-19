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
