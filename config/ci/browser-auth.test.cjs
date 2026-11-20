const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { test } = require('node:test');
const { browserSession } = require('./browser-session.cjs');

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

async function withAccounts(count, check, names = []) {
    const accounts = [];
    try {
        for (let index = 0; index < count; index++) {
            const name = names[index] || `auth-audit-${randomUUID()}`;
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


async function browserLogin(browser, account, password = account.password) {
    return browser.request('loginAction.do', {
        method: 'POST', body: new URLSearchParams({ username: account.name, password }),
    });
}

test('browser login rotates the session, blocks old cookies, and logout removes access', async () => {
    await withAccounts(1, async ([account]) => {
        const privateName = `browser-private-${randomUUID()}`;
        await createList(account.token, privateName);
        const browser = browserSession(mine);
        await browser.request('begin.do');
        const before = browser.cookies.get('JSESSIONID');
        assert.ok(before);
        const signedIn = await browserLogin(browser, account);
        assert.ok(signedIn.includes(account.name));
        assert.ok(signedIn.includes(privateName));
        const authenticated = browser.cookies.get('JSESSIONID');
        const replay = browserSession(mine);
        replay.cookies.set('JSESSIONID', before);
        const replayed = await replay.request('mymine.do');
        const loggedOut = await browser.request('logout.do');
        assert.deepEqual({ rotated: before !== authenticated,
            replayHasPrivateList: replayed.includes(privateName),
            logoutRotated: browser.cookies.get('JSESSIONID') !== authenticated,
            logoutHasPrivateList: loggedOut.includes(privateName),
        }, { rotated: true, replayHasPrivateList: false,
            logoutRotated: true, logoutHasPrivateList: false });
    });
});

test('failed browser login preserves anonymous lists and does not authenticate', async () => {
    await withAccounts(1, async ([account]) => {
        const privateName = `failed-private-${randomUUID()}`;
        await createList(account.token, privateName);
        const guestName = `failed-guest-${randomUUID()}`;
        const result = await api('search', undefined, {}, { q: 'david' });
        const member = result.results.find(item => item.fields.name === 'David Brent');
        assert.ok(member);
        const browser = browserSession(mine);
        await browser.request('begin.do');
        await browser.request('saveFromIdsToBag.do', {
            method: 'POST', body: new URLSearchParams({ type: 'Employee', allChecked: 'false',
                ids: String(member.id), newBagName: guestName, source: 'keywordSearchResults' }),
        });
        const before = browser.cookies.get('JSESSIONID');
        await browserLogin(browser, account, 'incorrect-' + randomUUID());
        const anonymous = await browser.request('mymine.do');
        assert.equal(browser.cookies.get('JSESSIONID'), before);
        assert.ok(anonymous.includes(guestName));
        assert.ok(!anonymous.includes(privateName));
        assert.equal(await ownsList(account.token, guestName + '_1'), false);
        await browserLogin(browser, account);
        assert.deepEqual(await members(account.token, guestName + '_1'), [[member.id]]);
        await browser.request('logout.do');
    });
});

test('switching from an administrator removes privilege and preserves a usable MyMine page', async () => {
    await withAccounts(1, async ([account]) => {
        const browser = browserSession(mine);
        await browser.request('begin.do');
        try {
            const admin = await browserLogin(browser, {
                name: 'superuser@intermine.org', password: 'intermine',
            });
            assert.ok(admin.includes('class="admin-msg"'));
            await browser.request('mymine.do?subtab=users');
            const ordinary = await browserLogin(browser, account);
            assert.ok(ordinary.includes(account.name));
            assert.ok(!ordinary.includes('class="admin-msg"'));
            await browser.request('showProperties.do', {}, 403);
            await browser.request('initUsers.do', {}, 403);
        } finally {
            await browser.request('logout.do');
        }
    });
});

test('administrator user lists render account names as text', async () => {
    const name = `<em data-auth-audit=${randomUUID()}>user</em>`;
    await withAccounts(1, async () => {
        const browser = browserSession(mine);
        await browser.request('begin.do');
        try {
            await browserLogin(browser, {
                name: 'superuser@intermine.org', password: 'intermine',
            });
            const page = await browser.request('mymine.do?subtab=users');
            assert.equal(page.includes(name), false, 'Account names must not become HTML');
            assert.ok(page.includes(name.replaceAll('<', '&lt;').replaceAll('>', '&gt;')));
        } finally {
            await browser.request('logout.do');
        }
    }, [name]);
});


test('API-key pages keep quoted account names outside inline JavaScript', async () => {
    const name = `quoted-'"</script><em data-auth-audit=${randomUUID()}>user</em>`;
    await withAccounts(1, async ([account]) => {
        const browser = browserSession(mine);
        await browser.request('begin.do');
        try {
            await browserLogin(browser, account);
            const page = await browser.request('mymine.do?subtab=account');
            assert.equal(page.includes(name), false, 'Account data must not become script source');
            assert.ok(page.includes('id="current-user"'));
            assert.ok(page.includes('&lt;/script&gt;&lt;em'));
        } finally {
            await browser.request('logout.do');
        }
    }, [name]);
});

test('server-rendered list sharing escapes the recipient account name', async () => {
    const recipientName = `share-'"<em data-auth-audit=${randomUUID()}>user</em>`;
    await withAccounts(2, async ([owner, recipient]) => {
        const list = `shared-browser-${randomUUID()}`;
        await createList(owner.token, list);
        await api('lists/shares', owner.token, { method: 'POST',
            body: new URLSearchParams({ list, with: recipient.name, notify: 'false' }),
        });
        const browser = browserSession(mine);
        await browser.request('begin.do');
        try {
            const page = await browserLogin(browser, owner);
            assert.equal(page.includes(recipientName), false);
            assert.ok(page.includes('data-user="share-'));
            assert.ok(page.includes('&lt;em data-auth-audit='));
            assert.ok(page.includes('data-bag-name="' + list + '"'));
        } finally {
            await browser.request('logout.do');
        }
    }, [undefined, recipientName]);
});

function decodeAttribute(value) {
    const entities = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
    return value.replace(/&(#x[\da-f]+|#\d+|amp|lt|gt|quot|apos);/gi, (entity, key) => {
        if (key.startsWith('#x')) return String.fromCodePoint(parseInt(key.slice(2), 16));
        if (key.startsWith('#')) return String.fromCodePoint(parseInt(key.slice(1), 10));
        return entities[key];
    });
}

test('role checkbox values preserve account identity after HTML decoding', async () => {
    const name = `role-&-"quoted"-<em>-${randomUUID()}`;
    await withAccounts(1, async () => {
        const browser = browserSession(mine);
        await browser.request('begin.do');
        try {
            await browserLogin(browser, { name: 'superuser@intermine.org', password: 'intermine' });
            const page = await browser.request('mymine.do?subtab=users');
            const boxes = [...page.matchAll(/<input\b[^>]*\bname="superUsers"[^>]*>/g)]
                .map(([tag]) => ({ value: decodeAttribute(tag.match(/\bvalue="([^"]*)"/)[1]),
                    checked: /\bchecked(?:=|\s|>)/.test(tag) }));
            assert.ok(boxes.some(box => box.value === name && !box.checked));
            assert.ok(boxes.some(box => box.value === 'superuser@intermine.org' && box.checked));
        } finally {
            await browser.request('logout.do');
        }
    }, [name]);
});
