const assert = require('node:assert/strict');

function browserSession(mine) {
    const cookies = new Map();
    async function request(path, options = {}, status = 200) {
        let url = new URL(path, mine);
        for (let redirects = 0; redirects < 10; redirects++) {
            const response = await fetch(url, {
                ...options, redirect: 'manual', signal: AbortSignal.timeout(15000),
                headers: { ...options.headers,
                    Cookie: [...cookies].map(([key, value]) => `${key}=${value}`).join('; ') },
            });
            for (const cookie of response.headers.getSetCookie()) {
                const pair = cookie.split(';', 1)[0];
                const separator = pair.indexOf('=');
                cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
            }
            if (response.status >= 300 && response.status < 400) {
                url = new URL(response.headers.get('location'), url);
                if (response.status === 303 || response.status === 302) options = {};
                continue;
            }
            assert.equal(response.status, status, `${url.pathname}: ${response.status}`);
            return response.text();
        }
        throw new Error('Too many application redirects');
    }
    return { request, cookies };
}

module.exports = { browserSession };
