package org.intermine.api.searchengine.solr;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import junit.framework.TestCase;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;

public class SolrClientFactoryTest extends TestCase {
    public void testRoutesRootCore() throws Exception {
        assertRoute("/solr/intermine-search", "");
    }

    public void testRoutesPrefixedCoreWithTrailingSlash() throws Exception {
        assertRoute("/proxy/solr/intermine-autocomplete", "/");
    }

    public void testRejectsInvalidCoreUrls() throws Exception {
        for (String url : new String[] {"ftp://localhost/solr/core", "http://localhost/solr",
                "http://localhost/", "http://localhost/solr/core?q=value",
                "http://localhost/solr/core#fragment", "http://user:pass@localhost/solr/core",
                "http://localhost/solr/../core", "http://localhost/solr/a%2Fb"}) {
            try (SolrClient ignored = SolrClientFactory.create(url)) {
                fail("Invalid core URL accepted: " + url);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    private void assertRoute(String corePath, String suffix) throws Exception {
        AtomicReference<String> requestedPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            byte[] body = "{\"error\":{\"msg\":\"Injected failure\",\"code\":500}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try (SolrClient client = SolrClientFactory.create("http://127.0.0.1:"
                + server.getAddress().getPort() + corePath + suffix)) {
            try {
                client.query(new SolrQuery("*:*"));
                fail("The server returned a deliberate failure");
            } catch (SolrServerException | org.apache.solr.common.SolrException expected) {
                assertEquals(corePath + "/select", requestedPath.get());
            }
        } finally {
            server.stop(0);
        }
    }
}
