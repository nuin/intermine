const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { JSDOM } = require('jsdom');
const scripts = path.resolve(__dirname, '../../../intermine/webapp/src/main/webapp/js');

function page(t) {
    const dom = new JSDOM('<div id="sharingUsers-1"></div><input id="userValue-1">',
        { runScripts: 'outside-only', url: 'http://localhost/' });
    t.after(() => dom.window.close());
    // Exercise the controls against the legacy jQuery distributed with this webapp.
    dom.window.eval(fs.readFileSync(path.join(scripts, 'jquery-1.7.js'), 'utf8'));
    dom.window.eval(fs.readFileSync(path.join(scripts, 'shareBag.js'), 'utf8'));
    return dom.window;
}

test('sharing names are text and removal receives the original name and list', t => {
    const window = page(t);
    const user = '<em data-audit="account">person</em>';
    const bag = 'list "one" <two>';
    let removed;
    window.AjaxServices = { deleteUserToShareBag(name, list) { removed = { name, list }; } };
    window.addUserSpan('1', user, bag);
    const container = window.document.getElementById('sharingUsers-1');
    assert.equal(container.querySelector('em'), null);
    assert.equal(container.textContent, user + '[x]');
    window.jQuery(container.querySelector('a')).trigger('click');
    assert.deepEqual(removed, { name: user, list: bag });
});

test('invitation errors render server messages and entered names as text', t => {
    const window = page(t);
    const user = '<em data-audit="invite">person</em>';
    const error = 'User ' + user + ' not found';
    let notification;
    window.FailureNotification = function(options) {
        notification = options.message;
        this.render = () => {};
    };
    window.AjaxServices = { addUserToShareBag(name, list, callback) { callback(error); } };
    window.jQuery('#userValue-1').val(user);
    window.addUser('1', 'list');
    assert.equal(notification.find('em').length, 0);
    assert.equal(notification.text(), error + ' Would you like to send an invitation to '
        + user + '? OK');
});

test('refresh replaces sharing names without leaking loop variables', t => {
    const window = page(t);
    window.displayUsers('1', ['first', 'second'], 'list');
    window.displayUsers('1', ['replacement'], 'list');
    assert.equal(window.jQuery('#sharingUsers-1').text(), 'replacement[x]');
    assert.equal(Object.hasOwn(window, 'i'), false);
    assert.equal(Object.hasOwn(window, 'user'), false);
});

test('server-rendered sharing controls preserve data without inline JavaScript', t => {
    const window = page(t);
    const user = "person's <name>";
    const bag = 'list "one" <two>';
    const container = window.document.createElement('span');
    container.className = 'share-bag';
    container.setAttribute('data-share-id', '1');
    container.setAttribute('data-bag-name', bag);
    container.innerHTML = '<a class="deleteTagLink">[x]</a><button class="add-sharing-user">Add</button>';
    container.querySelector('a').setAttribute('data-user', user);
    window.document.body.append(container);
    const calls = [];
    window.AjaxServices = {
        deleteUserToShareBag(name, list) { calls.push(['remove', name, list]); },
        addUserToShareBag(name, list) { calls.push(['add', name, list]); },
    };
    window.jQuery(container.querySelector('a')).trigger('click');
    window.jQuery('#userValue-1').val(user);
    window.jQuery(container.querySelector('button')).trigger('click');
    assert.deepEqual(calls, [['remove', user, bag], ['add', user, bag]]);
});

test('failed sharing invitations keep service errors as text', t => {
    const window = page(t);
    const error = '<em data-audit="service">invitation failed</em>';
    const messages = [];
    window.FailureNotification = function(options) {
        messages.push(options.message);
        this.render = () => {};
        this.close = () => {};
    };
    window.AjaxServices = { addUserToShareBag(name, list, callback) {
        callback('User not found');
    } };
    window.$SERVICE = { fetchList(list, callback) {
        callback({ inviteUserToShare() { return { fail(handler) { handler(error); } }; } });
        return { fail() {} };
    } };
    window.jQuery('#userValue-1').val('recipient');
    window.addUser('1', 'list');
    messages[0].find('button').trigger('click');
    const rendered = window.jQuery('<div>').append(messages[1]);
    assert.equal(rendered.find('em').length, 0);
    assert.equal(rendered.text(), error);
});

async function preferencesPage(t) {
    const dom = new JSDOM('<table class="user-preferences"><tbody><tr><td></td><td>'
        + '<form class="editable-user-preference"><input type="text"><button class="update">Update</button></form>'
        + '</td></tr></tbody></table><button class="add-user-preference">Add</button>'
        + '<form class="add-user-preference"><input class="prefname"><input class="prefvalue"></form>'
        + '<div id="notifications"></div>', { runScripts: 'outside-only', url: 'http://localhost/' });
    t.after(() => dom.window.close());
    const window = dom.window;
    window.eval(fs.readFileSync(path.join(scripts, 'jquery-1.7.js'), 'utf8'));
    window.jQuery.fx.off = true;
    window._ = { contains: (values, value) => values.includes(value) };
    window.FailureNotification = window.Notification = function(options) {
        this.render = () => window.jQuery('<div>').append(options.message).appendTo('#notifications');
    };
    window.eval(fs.readFileSync(path.join(scripts, 'mymine.js'), 'utf8'));
    await new Promise(resolve => window.jQuery(resolve));
    return window;
}

test('duplicate preference names stay text in notifications', async t => {
    const window = await preferencesPage(t);
    const name = '<em data-pref="duplicate">key</em>';
    window.jQuery('.editable-user-preference input').attr('name', name);
    window.jQuery('.prefname').val(name);
    window.jQuery('.prefvalue').val('value');
    window.jQuery('form.add-user-preference').trigger('submit');
    const notice = window.document.getElementById('notifications');
    assert.equal(notice.querySelector('em'), null);
    assert.equal(notice.textContent, name + ' is already set. Please update or delete the current value');
});

test('preference service failures stay text in notifications', async t => {
    const window = await preferencesPage(t);
    const error = '<em data-pref="error">preference failed</em>';
    window.$SERVICE = { manageUserPreferences() {
        return { then(success, failure) {
            failure({ responseText: JSON.stringify({ error }) }, 'error', 'Bad Request');
        } };
    } };
    window.jQuery('.editable-user-preference input').attr('name', 'aka').val('value');
    window.jQuery('button.update').trigger('click');
    const notice = window.document.getElementById('notifications');
    assert.equal(notice.querySelector('em'), null);
    assert.equal(notice.textContent, 'Could not change preference - ' + error);
});

test('preference updates send original values without leaking handler variables', async t => {
    const window = await preferencesPage(t);
    const name = 'key "<&>';
    const value = 'value "<&>';
    const windowName = window.name;
    const calls = [];
    window.$SERVICE = { manageUserPreferences(method, values) {
        calls.push([method, ...Array.from(values, pair => Array.from(pair))]);
        return { then(success) { success(); } };
    } };
    window.jQuery('.editable-user-preference input').attr('name', name).val(value);
    window.jQuery('button.update').trigger('click');
    assert.deepEqual(calls, [['POST', [name, value]]]);
    assert.equal(Object.hasOwn(window, '$btn'), false);
    assert.equal(Object.hasOwn(window, '$row'), false);
    assert.equal(Object.hasOwn(window, '$input'), false);
    assert.equal(window.name, windowName);
});
