const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { test } = require('node:test');
const { browserSession } = require('./browser-session.cjs');

assert.ok(process.env.TESTMODEL_URL, 'TESTMODEL_URL must identify the disposable test mine');
const mine = new URL(process.env.TESTMODEL_URL.replace(/\/$/, '') + '/');
const service = new URL('service/', mine);

async function json(path, params = {}, options = {}, status = 200) {
    const url = new URL(path, service);
    url.search = new URLSearchParams({ format: 'json', ...params });
    const response = await fetch(url, { ...options, signal: AbortSignal.timeout(15000) });
    const body = await response.text();
    assert.equal(response.status, status, `${path}: ${body}`);
    const data = JSON.parse(body);
    if (status === 200) assert.equal(data.wasSuccessful, true, body);
    return data;
}

test('keyword search preserves matches, facets, and complete pagination', async () => {
    const david = await json('search', { q: 'david' });
    assert.equal(david.totalHits, 2);
    assert.deepEqual(david.facets.Category, { Manager: 1, Department: 1 });
    const department = await json('search', { q: 'david', facet_Category: 'Department' });
    assert.equal(department.totalHits, 1);
    assert.equal(department.results[0].type, 'Department');
    assert.equal((await json('search', { q: 'Human Resources' })).totalHits, 19);
    assert.equal((await json('search', { q: 'nonexistentvalidationterm' })).totalHits, 0);
    const ids = [];
    for (let start = 0; start < 407; start += 40) {
        const page = await json('search', { start, size: 40 });
        assert.equal(page.totalHits, 407);
        assert.equal(page.results.length, Math.min(40, 407 - start));
        ids.push(...page.results.map(item => item.id));
    }
    assert.equal(new Set(ids).size, 407);
});

test('search list restrictions return exactly the authorized members', async () => {
    for (const [list, token, size] of [
        ['Everyones-Favourite-Employees', '', 7],
        ['My-Favourite-Employees', 'test-user-token', 4],
    ]) {
        const result = await json('search', { list, token });
        const query = `<query model="testmodel" view="Employee.id">`
            + `<constraint path="Employee" op="IN" value="${list}"/></query>`;
        const members = await json('query/results', { query, token });
        assert.equal(result.totalHits, size);
        assert.deepEqual(new Set(result.results.map(item => item.id)),
            new Set(members.results.map(row => row[0])));
    }
    await json('search', { list: 'My-Favourite-Employees' }, {}, 400);
});

test('an empty authorized list returns no search hits', async () => {
    const name = `empty-search-${randomUUID()}`;
    const token = 'test-user-token';
    const source = 'Everyones-Favourite-Employees';
    await json('lists/subtract', { token }, {
        method: 'POST',
        body: new URLSearchParams({ name, lists: source, references: source }),
    });
    try {
        const result = await json('search', { list: name, token });
        assert.equal(result.totalHits, 0);
        assert.deepEqual(result.results, []);
    } finally {
        await json('lists', { name, token }, { method: 'DELETE' });
    }
});

test('DWR autocomplete returns indexed attribute suggestions', async () => {
    const { request, cookies } = browserSession(mine);
    await request('begin.do');
    const engine = await request('dwr/engine.js');
    const match = engine.match(/_origScriptSessionId\s*=\s*"([^"]+)"/);
    assert.ok(match, 'DWR must supply a script session identifier');
    for (const [query, expected] of [
        ['Da', ['Dan Gore', 'David Brent']], ['Dav', ['David Brent']],
        ['David B', ['David Brent']], ['nonexistentvalidationterm', []],
    ]) {
        const body = [
            'callCount=1', `page=${mine.pathname}begin.do`,
            `httpSessionId=${cookies.get('JSESSIONID') || ''}`,
            `scriptSessionId=${match[1]}${randomUUID()}`, 'c0-scriptName=AjaxServices',
            'c0-methodName=getContent', 'c0-id=0', `c0-param0=string:${query}`,
            'c0-param1=boolean:false', 'c0-param2=string:name',
            'c0-param3=string:Employee', 'batchId=0', '',
        ].join('\n');
        const response = await request('dwr/call/plaincall/AjaxServices.getContent.dwr', {
            method: 'POST', headers: { 'Content-Type': 'text/plain' }, body,
        });
        const callback = response.match(/_remoteHandleCallback\('0','0',(\[.*\])\);/);
        assert.ok(callback, response);
        const result = JSON.parse(callback[1]);
        assert.equal(result[0], 'true');
        assert.deepEqual(result.slice(1).sort(), expected);
    }
});

async function withSearchUser(check) {
    const name = `search-list-${randomUUID()}`;
    const password = randomUUID();
    const registration = await json('users', {}, {
        method: 'POST', body: new URLSearchParams({ name, password }),
    });
    const token = registration.user.temporaryToken;
    const browser = browserSession(mine);
    try {
        await browser.request('begin.do');
        const loggedIn = await browser.request('loginAction.do', {
            method: 'POST', body: new URLSearchParams({ username: name, password }),
        });
        assert.ok(loggedIn.includes(name), "Browser login must select the test account");
        await check(token, browser);
    } finally {
        await browser.request('logout.do');
        const deletion = await json('user/deregistration', { token }, { method: 'POST' });
        const url = new URL('user', service);
        url.search = new URLSearchParams({ token, deregistrationToken: deletion.token.uuid,
            format: 'xml' });
        const response = await fetch(url, { method: 'DELETE', signal: AbortSignal.timeout(15000) });
        assert.ok(response.ok, await response.text());
    }
}

function selectAllForm(source, destination) {
    return new URLSearchParams({ type: 'Employee', allChecked: 'true', totalHits: '1',
        searchTerm: 'Human Resources', searchBag: source, newBagName: destination,
        source: 'keywordSearchResults',
        jsonFacets: JSON.stringify({ facets: [{ facetName: 'Category', facetValue: 'Employee' }] }),
    });
}

test('browser Select All saves only members of the source list', async () => {
    await withSearchUser(async (token, browser) => {
        const source = `source-${randomUUID()}`;
        const destination = `selected-${randomUUID()}`;
        const matches = await json('search', { q: 'Human Resources', facet_Category: 'Employee' });
        const member = matches.results[5];
        assert.notEqual(member.id, matches.results[0].id);
        await json('lists', { token, name: source, type: 'Employee' }, {
            method: 'POST', headers: { 'Content-Type': 'text/plain' },
            body: JSON.stringify(member.fields.name),
        });
        const page = await browser.request('keywordSearchResults.do?' + new URLSearchParams({
            searchTerm: 'Human Resources', searchBag: source, facet_Category: 'Employee',
        }));
        await browser.request('saveFromIdsToBag.do', {
            method: 'POST', body: selectAllForm(source, destination),
        });
        const savedName = (await json('lists', { token })).lists
            .find(list => list.name.startsWith(destination)).name;
        const saved = await json('search', { token, list: savedName });
        assert.deepEqual(saved.results.map(item => item.id), [member.id]);
        assert.match(page, new RegExp(`name="searchBag"[^>]*value="${source}"`));
    });
});

test('browser Select All rejects a source list that is no longer accessible', async () => {
    await withSearchUser(async (token, browser) => {
        const source = `revoked-${randomUUID()}`;
        const destination = `must-not-exist-${randomUUID()}`;
        await json('lists', { token, name: source, type: 'Employee' }, {
            method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: '"Dan Gore"',
        });
        await json('lists', { token, name: source }, { method: 'DELETE' });
        await browser.request('saveFromIdsToBag.do', {
            method: 'POST', body: selectAllForm(source, destination),
        }, 400);
        assert.ok(!(await json('lists', { token })).lists.some(list => list.name.startsWith(destination)));
    });
});
