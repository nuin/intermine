package org.intermine.web.autocompletion;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import junit.framework.TestCase;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.util.NamedList;
import org.easymock.EasyMock;
import org.intermine.metadata.Model;
import org.intermine.objectstore.ObjectStore;
import org.intermine.objectstore.query.Query;
import org.intermine.objectstore.query.Results;
import org.intermine.objectstore.query.ResultsRow;

public class AutoCompleterTest extends TestCase {
    public void testSchemaReadFailureStopsRebuild() throws Exception {
        assertStopsAtFailure(3);
    }

    public void testSchemaWriteFailureStopsRebuild() throws Exception {
        assertStopsAtFailure(4);
    }

    public void testDocumentWriteFailureStopsRebuild() throws Exception {
        assertStopsAtFailure(6);
    }

    public void testFinalCommitFailureStopsRebuild() throws Exception {
        assertStopsAtFailure(7);
    }

    private void assertStopsAtFailure(int failAt) throws Exception {
        Map<String, String> configured = PropertiesManager.getInstance().getClassFieldMap();
        Map<String, String> original = new HashMap<>(configured);
        configured.clear();
        configured.put("org.intermine.model.testmodel.Employee", "name");
        try (FailingClient client = new FailingClient(failAt)) {
            ObjectStore store = EasyMock.createMock(ObjectStore.class);
            EasyMock.expect(store.getModel()).andReturn(Model.getInstanceByName("testmodel"))
                    .anyTimes();
            Results rows = EasyMock.createMock(Results.class);
            EasyMock.expect(rows.iterator()).andReturn(Collections.<Object>singletonList(
                    new ResultsRow<>(Collections.singletonList("David Brent"))).iterator());
            EasyMock.expect(store.execute(EasyMock.anyObject(Query.class))).andReturn(rows);
            EasyMock.replay(store, rows);
            try {
                new AutoCompleter(store).buildIndex(store, client);
                fail("A failed Solr prerequisite must fail the autocomplete rebuild");
            } catch (SolrServerException error) {
                assertSame(client.failure, error);
            }
            assertEquals(failAt, client.requests);
            EasyMock.verify(store, rows);
        } finally {
            configured.clear();
            configured.putAll(original);
        }
    }

    private static final class FailingClient extends SolrClient {
        private final int failAt;
        private final SolrServerException failure = new SolrServerException("Injected failure");
        private int requests;

        FailingClient(int failAt) {
            this.failAt = failAt;
        }

        @Override
        public NamedList<Object> request(SolrRequest<?> request, String collection)
                throws SolrServerException {
            if (++requests >= failAt) {
                throw failure;
            }
            NamedList<Object> response = new NamedList<>();
            NamedList<Object> header = new NamedList<>();
            header.add("status", 0);
            response.add("responseHeader", header);
            response.add("fields", Collections.emptyList());
            return response;
        }

        @Override
        public void close() { }
    }
}
