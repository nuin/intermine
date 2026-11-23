package org.intermine.bio.dataconversion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;

public class PublicationCacheTest extends TestCase
{
    public void testReadsExistingPublicationFormat() throws Exception {
        Map<String, Object> publication = new HashMap<>();
        publication.put("id", "123");
        publication.put("year", "2026");
        publication.put("title", "A publication");
        publication.put("authors", new ArrayList<>(List.of("First Author", "Second Author")));
        publication.put("meshTerms", new ArrayList<>(List.of("Genetics")));
        assertEquals(publication,
                EntrezPublicationsRetriever.readCachedPublication(serialize(publication)));
    }

    public void testRejectsUnexpectedClassBeforeItsCallbackRuns() throws Exception {
        Probe.executed = false;
        Map<String, Object> publication = new HashMap<>();
        publication.put("title", new Probe());
        try {
            EntrezPublicationsRetriever.readCachedPublication(serialize(publication));
            fail("Unexpected cache class must be rejected");
        } catch (InvalidClassException e) {
            assertFalse("Deserialization callback must not run", Probe.executed);
        }
    }

    public void testRejectsDeeplyNestedCacheData() throws Exception {
        Map<String, Object> publication = new HashMap<>();
        Map<String, Object> nested = publication;
        for (int i = 0; i < 20; i++) {
            Map<String, Object> child = new HashMap<>();
            nested.put("nested", child);
            nested = child;
        }
        try {
            EntrezPublicationsRetriever.readCachedPublication(serialize(publication));
            fail("Excessive cache nesting must be rejected");
        } catch (InvalidClassException e) {
            assertTrue(e.getMessage().contains("filter status"));
        }
    }

    public void testRejectsOversizedPublication() throws Exception {
        Map<String, Object> publication = new HashMap<>();
        publication.put("abstractText", "x".repeat(8 * 1024 * 1024));
        try {
            EntrezPublicationsRetriever.readCachedPublication(serialize(publication));
            fail("Oversized cache entry must be rejected");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("too large"));
        }
    }

    private static byte[] serialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static final class Probe implements Serializable
    {
        private static final long serialVersionUID = 1L;
        private static boolean executed;

        private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
            input.defaultReadObject();
            executed = true;
        }
    }
}
