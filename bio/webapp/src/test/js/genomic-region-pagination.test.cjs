const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const {test} = require('node:test');
const vm = require('node:vm');

const script = readFileSync(resolve(__dirname,
    '../../main/webapp/model/genomic_region_search/genomic_region_search_results_default.js'), 'utf8');

function search(total, finished = total) {
    const requests = [];
    const timers = new Map();
    let nextTimer = 0;
    const jQuery = () => ({ready() {}, html() {}});
    jQuery.post = (url, data) => requests.push({url, ...data});
    const context = vm.createContext({
        document: {}, jQuery, span_query_total_count: total, span_uuid_string: 'test-search',
        setInterval(callback) {
            timers.set(++nextTimer, callback);
            return nextTimer;
        },
        clearInterval(id) { timers.delete(id); }
    });
    vm.runInContext(script, context);
    context.finishedQueryCount = finished;
    context.is_all_queries_finished = finished === total;
    return {context, requests, timers};
}

function expectedRequest(fromIdx, toIdx) {
    return {url: 'genomicRegionSearchAjax.do', spanUUIDString: 'test-search',
        getData: 'true', fromIdx, toIdx};
}

for (const [total, size, page, first, last] of [
    [9, 10, 1, 0, 8], [24, 25, 1, 0, 23],
    [10, 10, 1, 0, 9], [25, 25, 1, 0, 24],
    [11, 10, 2, 10, 10], [19, 10, 2, 10, 18],
    [30, 10, 1, 0, 9], [0, 10, 1, 0, -1]
]) {
    test(`${total} regions, size ${size}, page ${page}: inclusive bounds`, () => {
        const {context, requests, timers} = search(total);
        context.loadResultData(size, page);
        assert.deepEqual(requests, [expectedRequest(first, last)]);
        assert.equal(timers.size, 0);
    });
}

test('a complete page loads while other regions are still running', () => {
    const {context, requests, timers} = search(20, 10);
    context.loadResultData(10, 1);
    assert.deepEqual(requests, [expectedRequest(0, 9)]);
    assert.equal(timers.size, 0);
});

test('an incomplete page waits until its last region finishes', () => {
    const {context, requests, timers} = search(20, 9);
    context.loadResultData(10, 1);
    assert.equal(requests.length, 0);
    assert.equal(timers.size, 1);
    const tick = [...timers.values()][0];
    tick();
    assert.equal(requests.length, 0);
    context.finishedQueryCount = 10;
    tick();
    assert.deepEqual(requests, [expectedRequest(0, 9)]);
    assert.equal(timers.size, 0);
});

test('changing page cancels polling for the previous page', () => {
    const {context, requests, timers} = search(20, 10);
    context.loadResultData(10, 2);
    assert.equal(timers.size, 1);
    context.loadResultData(5, 1);
    assert.deepEqual(requests, [expectedRequest(0, 4)]);
    assert.equal(timers.size, 0);
});
