package org.intermine.api.searchengine.solr;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.net.ServerSocket;
import java.io.IOException;

import junit.framework.TestCase;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.intermine.model.testmodel.Department;
import org.intermine.objectstore.ObjectStoreWriter;
import org.intermine.objectstore.ObjectStoreWriterFactory;

public class SolrIndexHandlerTest extends TestCase {
    public void testDeleteFailureStopsRebuild() throws Exception {
        assertStopsAtFailure(1);
    }

    public void testInitialCommitFailureStopsRebuild() throws Exception {
        assertStopsAtFailure(2);
    }

    public void testSchemaReadFailureStopsRebuild() throws Exception {
        assertStopsAtFailure(3);
    }

    public void testSchemaWriteFailureStopsRebuild() throws Exception {
        assertStopsAtFailure(6);
    }

    public void testUnavailableServerFailsRebuild() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        try (SolrClient client = SolrClientFactory.create(
                "http://127.0.0.1:" + port + "/solr/intermine-search")) {
            try {
                new SolrIndexHandler().createIndex(null, Collections.emptyMap(), client);
            } catch (IOException | SolrServerException expected) {
                return;
            }
            fail("An unavailable server must fail the rebuild");
        }
    }

    public void testSuccessfulHttpResponseWithSchemaErrorsIsRejected() throws Exception {
        NamedList<Object> response = new NamedList<>();
        NamedList<Object> header = new NamedList<>();
        header.add("status", 0);
        response.add("responseHeader", header);
        response.add("errors", Collections.singletonList("Invalid field"));
        UpdateResponse update = new UpdateResponse();
        update.setResponse(response);
        try {
            SolrIndexSupport.check(update, "add field");
            fail("Schema errors must fail even with status zero");
        } catch (SolrServerException expected) {
            assertTrue(expected.getMessage().contains("Invalid field"));
        }
    }

    public void testCancellationStopsFetcherBlockedOnFullQueue() throws Exception {
        ObjectStoreWriter writer = ObjectStoreWriterFactory.getObjectStoreWriter("osw.unittest");
        Department first = new Department();
        Department second = new Department();
        first.setName("First cancellation test department");
        second.setName("Second cancellation test department");
        CountDownLatch blocked = new CountDownLatch(1);
        ArrayBlockingQueue<SolrInputDocument> queue = new ArrayBlockingQueue<>(1) {
            @Override
            public void put(SolrInputDocument document) throws InterruptedException {
                if (remainingCapacity() == 0) {
                    blocked.countDown();
                }
                super.put(document);
            }
        };
        try (FailingClient client = new FailingClient(Integer.MAX_VALUE)) {
            SolrObjectHandler fetcher = new SolrObjectHandler(writer.getObjectStore(),
                    Collections.emptyMap(), queue, Collections.emptySet(), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyMap(), new Vector<>(),
                    Collections.emptyMap(), client, new ArrayList<>(), new ArrayList<>());
            try {
                writer.store(first);
                writer.store(second);
                fetcher.start();
                assertTrue("Fetcher never filled the queue: " + fetcher.getException(),
                        blocked.await(10, TimeUnit.SECONDS));
                fetcher.interrupt();
                fetcher.join(10000);
                assertFalse("Cancelled fetcher must terminate", fetcher.isAlive());
                assertTrue(fetcher.isFinished());
                assertTrue(fetcher.getException() instanceof InterruptedException);
            } finally {
                fetcher.interrupt();
                fetcher.join(10000);
                writer.delete(first);
                writer.delete(second);
            }
        } finally {
            writer.close();
        }
    }

    private void assertStopsAtFailure(int failAt) throws Exception {
        try (FailingClient client = new FailingClient(failAt)) {
            try {
                new SolrIndexHandler().createIndex(null, Collections.emptyMap(), client);
                fail("A failed Solr prerequisite must fail the rebuild");
            } catch (Exception error) {
                assertSame(client.failure, error);
            }
            assertEquals("No requests may follow the failed prerequisite", failAt, client.requests);
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
            response.add("fieldTypes", Collections.emptyList());
            response.add("copyFields", Collections.emptyList());
            return response;
        }

        @Override
        public void close() { }
    }
}
