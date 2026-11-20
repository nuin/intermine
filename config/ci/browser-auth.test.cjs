const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { test } = require('node:test');

assert.ok(process.env.TESTMODEL_URL, 'TESTMODEL_URL must identify the disposable test mine');
const mine = new URL(process.env.TESTMODEL_URL.replace(/\/$/, '') + '/');
const service = new URL('service/', mine);

async function api(path, token, options = {}, params = {}) {
    const url = new URL(path, service);
    url.search = new URLSearchParams({ ...params, ...(token ? { token } : {}) });
    const response = await fetch(url, { ...options, signal: AbortSignal.timeout(15000) });
    const body = await response.text();
    assert.ok(response.ok, `${path}: ${response.status} ${body}`);
    return response.headers.get('content-type').includes('json') ? JSON.parse(body) : body;
}

async function withAccounts(count, check) {
    const accounts = [];
    try {
        for (let index = 0; index < count; index++) {
            const name = `auth-audit-${randomUUID()}`;
            const password = randomUUID();
            const result = await api('users', undefined, {
                method: 'POST', body: new URLSearchParams({ name, password }),
            });
            accounts.push({ name, password, token: result.user.temporaryToken });
        }
        await check(accounts);
    } finally {
        const deletions = await Promise.allSettled(accounts.map(deleteAccount));
        const errors = deletions.filter(result => result.status === 'rejected');
        if (errors.length) throw new AggregateError(errors.map(result => result.reason),
            'Test account cleanup failed');
    }
}

async function deleteAccount(account) {
    const deletion = await api('user/deregistration', account.token, { method: 'POST' });
    await api('user', account.token, { method: 'DELETE' }, {
        deregistrationToken: deletion.token.uuid, format: 'xml',
    });
}

async function createList(token, name) {
    await api('lists', token, { method: 'POST',
        headers: { 'Content-Type': 'text/plain' }, body: '"David Brent"',
    }, { name, type: 'Employee' });
}

async function ownsList(token, name) {
    return (await api('lists', token)).lists.some(list => list.name === name);
}

async function members(token, name) {
    const query = `<query model="testmodel" view="Employee.id">`
        + `<constraint path="Employee" op="IN" value="${name}"/></query>`;
    return (await api('query/results', token, {}, { query, format: 'json' })).results;
}

async function login(token, account) {
    const result = await api('login', token, { method: 'POST',
        body: new URLSearchParams({ username: account.name, password: account.password }),
    });
    assert.equal(result.wasSuccessful, true);
}

test('login transfers an anonymous list and removes access through its old token', async () => {
    await withAccounts(1, async ([owner]) => {
        const source = (await api('session')).token;
        const name = `login-list-${randomUUID()}`;
        await createList(source, name);
        const expected = await members(source, name);
        await login(source, owner);
        assert.deepEqual(await members(owner.token, name), expected);
        assert.equal(await ownsList(source, name), false);
    });
});

test('reusing an anonymous token cannot transfer a private list again', async () => {
    await withAccounts(2, async accounts => {
        const [first, second] = accounts;
        const source = (await api('session')).token;
        const name = `replay-list-${randomUUID()}`;
        await createList(source, name);
        const expected = await members(source, name);
        await login(source, first);
        await login(source, second);
        assert.equal(await ownsList(second.token, name), false);
        await deleteAccount(second);
        accounts.pop();
        assert.deepEqual(await members(first.token, name), expected);
    });
});

test('concurrent logins claim an anonymous list for only one account', async () => {
    await withAccounts(2, async accounts => {
        const source = (await api('session')).token;
        const name = `concurrent-login-${randomUUID()}`;
        await createList(source, name);
        const expected = await members(source, name);
        await Promise.all(accounts.map(account => login(source, account)));
        const owners = [];
        for (const account of accounts) {
            if (await ownsList(account.token, name)) owners.push(account);
        }
        assert.equal(owners.length, 1);
        assert.deepEqual(await members(owners[0].token, name), expected);
        assert.equal(await ownsList(source, name), false);
    });
});

test('logging in from a named account preserves its private lists', async () => {
    await withAccounts(2, async ([first, second]) => {
        const name = `private-login-${randomUUID()}`;
        await createList(first.token, name);
        const expected = await members(first.token, name);
        await login(first.token, second);
        assert.equal(await ownsList(second.token, name), false);
        assert.deepEqual(await members(first.token, name), expected);
    });
});
