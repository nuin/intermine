package org.intermine.web.registry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import junit.framework.TestCase;
import org.intermine.web.registry.model.Instance;

public class RegistryClientTest extends TestCase {
    private HttpServer server;
    private URI root;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private volatile String namespaceResponse = "{\"namespace\":\"testmine\"}";
    private volatile String instanceResponse = "{\"instance\":{\"namespace\":\"testmine\","
            + "\"name\":\"遺伝子 Mine\",\"url\":\"https://example.org/mine\"}}";
    private volatile int status = 200;
    private volatile boolean stallBody;

    @Override
    protected void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        root = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/registry/");
        server.createContext("/", this::respond);
        server.start();
    }

    @Override
    protected void tearDown() {
        releaseResponse.countDown();
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        try {
            requests.add(exchange.getRequestURI().toASCIIString());
            String body = exchange.getRequestURI().getPath().endsWith("/namespace")
                    ? namespaceResponse : instanceResponse;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", root + "redirected");
            exchange.sendResponseHeaders(status, bytes.length);
            if (stallBody) {
                releaseResponse.await(5, TimeUnit.SECONDS);
            }
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        } finally {
            exchange.close();
        }
    }

    private Instance lookup() throws IOException, InterruptedException {
        return new RegistryClient(root, Duration.ofSeconds(2))
                .findInstance("https://example.org/mine");
    }

    public void testRegistryLookupPreservesUnicodeAndEncodesMineUrl() throws Exception {
        Instance instance = new RegistryClient(root, Duration.ofSeconds(2))
                .findInstance("https://example.org/遺伝子?a=1&b=x y");
        assertEquals("遺伝子 Mine", instance.getName());
        assertEquals("testmine", instance.getNamespace());
        assertEquals(List.of("/registry/service/namespace?url=https%3A%2F%2Fexample.org%2F"
                + "%E9%81%BA%E4%BC%9D%E5%AD%90%3Fa%3D1%26b%3Dx+y",
                "/registry/service/instances/testmine"), requests);
    }

    public void testNamespaceRemainsOnePathSegment() throws Exception {
        namespaceResponse = "{\"namespace\":\"../x y?z#é\"}";
        assertNotNull(lookup());
        assertEquals("/registry/service/instances/%2E%2E%2Fx%20y%3Fz%23%C3%A9",
                requests.get(1));
    }

    public void testMissingNamespaceDoesNotFetchAnInstance() throws Exception {
        namespaceResponse = "{}";
        assertNull(lookup());
        assertEquals(1, requests.size());
    }

    public void testMissingInstanceReturnsNoMetadata() throws Exception {
        instanceResponse = "{\"statusCode\":200}";
        assertNull(lookup());
    }

    public void testNullInstanceResponseReturnsNoMetadata() throws Exception {
        instanceResponse = "null";
        assertNull(lookup());
    }

    public void testNonSuccessReturnsNoMetadata() throws Exception {
        status = 503;
        assertNull(lookup());
        assertEquals(1, requests.size());
    }

    public void testRedirectIsNotFollowed() throws Exception {
        status = 302;
        assertNull(lookup());
        assertEquals(1, requests.size());
    }

    public void testMalformedNamespaceResponseIsRejected() throws Exception {
        namespaceResponse = "{\"namespace\":\"testmine\"";
        assertInvalidJson();
    }

    public void testTrailingNamespaceJsonIsRejected() throws Exception {
        namespaceResponse += " {}";
        assertInvalidJson();
    }

    public void testTrailingInstanceJsonIsRejected() throws Exception {
        instanceResponse += " {}";
        assertInvalidJson();
    }

    private void assertInvalidJson() throws InterruptedException {
        try {
            lookup();
            fail("Malformed registry JSON must not become metadata");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    public void testResponseBodyIsSubjectToTimeout() throws Exception {
        stallBody = true;
        long start = System.nanoTime();
        try {
            new RegistryClient(root, Duration.ofMillis(150)).findInstance("https://example.org");
            fail("A stalled registry body must time out");
        } catch (HttpTimeoutException expected) {
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(
                    Duration.ofSeconds(3)) < 0);
        }
    }
}
