package org.intermine.webservice.server.output;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import junit.framework.TestCase;
import org.apache.commons.io.IOUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.xerial.snappy.Snappy;

public class RuntimeCompatibilityTest extends TestCase {
    public void testJerseyJsonRoundTrip() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            try {
                byte[] body = IOUtils.toByteArray(exchange.getRequestBody());
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        Client client = ClientBuilder.newClient().register(JacksonFeature.class);
        try {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("name", "BRCA1 – 遺伝子");
            payload.put("count", 9007199254740993L);
            payload.put("active", true);
            JsonNode response = client.target("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/echo").request()
                    .post(Entity.json(payload), JsonNode.class);
            assertEquals(payload.get("name"), response.get("name").asText());
            assertEquals(9007199254740993L, response.get("count").asLong());
            assertTrue(response.get("active").asBoolean());
        } finally {
            client.close();
            server.stop(0);
        }
    }

    public void testJenaTurtleRoundTrip() {
        Model source = ModelFactory.createDefaultModel();
        Model restored = ModelFactory.createDefaultModel();
        try {
            source.createResource("https://example.org/gene/BRCA1")
                    .addProperty(source.createProperty("https://example.org/name"), "遺伝子");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            source.write(output, "TURTLE");
            restored.read(new ByteArrayInputStream(output.toByteArray()), null, "TURTLE");
            assertEquals(1L, restored.size());
            assertTrue(source.isIsomorphicWith(restored));
        } finally {
            restored.close();
            source.close();
        }
    }

    public void testSnappyNativeRoundTrip() throws IOException {
        String text = "InterMine: BRCA1 – 遺伝子";
        byte[] compressed = Snappy.compress(text.getBytes(StandardCharsets.UTF_8));
        assertEquals(text, new String(Snappy.uncompress(compressed), StandardCharsets.UTF_8));
    }
}
