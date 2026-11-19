const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { test } = require('node:test');

assert.ok(process.env.TESTMODEL_URL, 'TESTMODEL_URL must identify the disposable test mine');
const root = process.env.TESTMODEL_URL.replace(/\/$/, '') + '/service/';

async function api(path, token, options = {}) {
    const url = new URL(path, root);
    if (token) url.searchParams.set('token', token);
    const response = await fetch(url, { ...options, signal: AbortSignal.timeout(15000) });
    const body = await response.text();
    assert.ok(response.ok, `${options.method || 'GET'} ${path}: ${response.status} ${body}`);
    return response.headers.get('content-type').includes('json') ? JSON.parse(body) : body;
}

async function withUser(sourceToken, check) {
    const registration = await api('users', sourceToken, {
        method: 'POST',
        body: new URLSearchParams({ name: `registration-${randomUUID()}`, password: randomUUID() }),
    });
    const token = registration.user.temporaryToken;
    try {
        await check(token);
    } finally {
        const deletion = await api('user/deregistration', token, { method: 'POST' });
        await api(`user?deregistrationToken=${deletion.token.uuid}&format=xml`, token, {
            method: 'DELETE',
        });
    }
}

async function createList(token, name) {
    const params = new URLSearchParams({ name, type: 'Employee' });
    await api(`lists?${params}`, token, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain' },
        body: '"David Brent"',
    });
}

async function listNames(token) {
    return (await api('lists', token)).lists.map(list => list.name);
}

test('registration preserves the logged-in account’s private lists', async () => {
    await withUser(undefined, async owner => {
        const name = `owner-list-${randomUUID()}`;
        await createList(owner, name);
        await withUser(owner, async newcomer => {
            assert.ok(!(await listNames(newcomer)).includes(name),
                'Registering a second account must not transfer a private list');
        });
        assert.ok((await listNames(owner)).includes(name),
            'Deleting the new account must preserve the original account’s list');
    });
});

test('registration retains lists created by an anonymous session', async () => {
    const session = await api('session');
    const name = `anonymous-list-${randomUUID()}`;
    await createList(session.token, name);
    await withUser(session.token, async newcomer => {
        assert.ok((await listNames(newcomer)).includes(name));
    });
});
