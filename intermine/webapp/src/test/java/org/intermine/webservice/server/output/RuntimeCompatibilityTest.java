package org.intermine.webservice.server.output;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.activation.DataHandler;
import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.mail.util.ByteArrayDataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import junit.framework.TestCase;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.LogFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.WriterAppender;

public class RuntimeCompatibilityTest extends TestCase {
    public void testJdkHttpJsonRoundTrip() throws Exception {
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
        try (HttpClient client = HttpClient.newHttpClient()) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("name", "BRCA1 – 遺伝子");
            payload.put("count", 9007199254740993L);
            payload.put("active", true);
            ObjectMapper mapper = new ObjectMapper();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/echo"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload)))
                    .build();
            JsonNode response = mapper.readTree(client.send(request,
                    HttpResponse.BodyHandlers.ofByteArray()).body());
            assertEquals(payload.get("name"), response.get("name").asText());
            assertEquals(9007199254740993L, response.get("count").asLong());
            assertTrue(response.get("active").asBoolean());
        } finally {
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

    public void testMailAttachmentRoundTrip() throws IOException, MessagingException {
        String text = "InterMine attachment: 遺伝子";
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(
                text.getBytes(StandardCharsets.UTF_8), "application/octet-stream")));
        part.setHeader("Content-Transfer-Encoding", "base64");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        part.writeTo(output);
        MimeBodyPart restored = new MimeBodyPart(new ByteArrayInputStream(output.toByteArray()));
        try (InputStream input = restored.getDataHandler().getInputStream()) {
            assertEquals(text, IOUtils.toString(input, StandardCharsets.UTF_8));
        }
    }

    public void testCommonsLoggingReachesConfiguredBackend() {
        assertLoggingReachesBackend(false);
    }

    public void testSlf4jLoggingReachesConfiguredBackend() {
        assertLoggingReachesBackend(true);
    }

    private void assertLoggingReachesBackend(boolean slf4j) {
        Logger logger = Logger.getLogger("intermine.runtime.compatibility");
        Level originalLevel = logger.getLevel();
        StringWriter output = new StringWriter();
        WriterAppender appender = new WriterAppender(new PatternLayout("%m"), output);
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            if (slf4j) {
                org.slf4j.LoggerFactory.getLogger(logger.getName()).info("Runtime logging: 遺伝子");
            } else {
                LogFactory.getLog(logger.getName()).info("Runtime logging: 遺伝子");
            }
            assertEquals("Runtime logging: 遺伝子", output.toString());
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(originalLevel);
            appender.close();
        }
    }
}
