package org.intermine.bio.dataconversion;

import java.io.File;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.Transaction;
import junit.framework.TestCase;
import org.intermine.model.bio.Publication;
import org.intermine.objectstore.ObjectStore;
import org.intermine.util.DynamicUtil;

public class PublicationCacheIntegrationTest extends TestCase
{
    private Path cacheDirectory;

    @Override
    protected void setUp() throws Exception {
        cacheDirectory = Files.createTempDirectory("publication-cache-test-");
    }

    @Override
    protected void tearDown() throws Exception {
        try (var paths = Files.walk(cacheDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    public void testTruncatedRecordCannotProduceSuccessfulIncompleteExport() throws Exception {
        seedCache(new byte[] {(byte) 0xac, (byte) 0xed});
        try {
            new Fixture("Valid title", new AtomicInteger(), "truncated.xml").execute();
            fail("A truncated cached publication must fail explicitly");
        } catch (RuntimeException e) {
            assertTrue("Failure must identify the requested publication",
                    String.valueOf(e.getCause().getMessage()).contains("10021333"));
        }
    }

    public void testLargeValidPublicationExportsOnTwoRunsWithoutPoisoningCache() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        String title = "x".repeat(8 * 1024 * 1024);
        for (int i = 0; i < 2; i++) {
            String output = "large-" + i + ".xml";
            new Fixture(title, fetches, output).execute();
            String exported = Files.readString(cacheDirectory.resolve(output));
            assertTrue(exported.contains("10021333"));
            assertTrue(exported.stripTrailing().endsWith("</items>"));
        }
        assertEquals("Oversized records must be fetched again, not cached", 2, fetches.get());
    }

    public void testDatabaseReadIsBoundedBeforeDeserialization() throws Exception {
        seedCache(new byte[9 * 1024 * 1024]);
        Environment env = openEnvironment();
        Database db = openDatabase(env);
        Transaction txn = env.beginTransaction(null, null);
        try {
            DatabaseEntry data = EntrezPublicationsRetriever.readCacheEntry(db, txn, key());
            assertNotNull(data);
            assertEquals(8 * 1024 * 1024 + 1, data.getSize());
            assertTrue(data.getPartial());
        } finally {
            txn.abort();
            db.close();
            env.close();
        }
    }

    private void seedCache(byte[] value) {
        Environment env = openEnvironment();
        Database db = openDatabase(env);
        Transaction txn = env.beginTransaction(null, null);
        try {
            db.put(txn, key(), new DatabaseEntry(value));
            txn.commit();
        } finally {
            db.close();
            env.close();
        }
    }

    private Environment openEnvironment() {
        EnvironmentConfig config = new EnvironmentConfig();
        config.setTransactional(true);
        config.setAllowCreate(true);
        return new Environment(cacheDirectory.toFile(), config);
    }

    private static Database openDatabase(Environment env) {
        DatabaseConfig config = new DatabaseConfig();
        config.setTransactional(true);
        config.setAllowCreate(true);
        config.setSortedDuplicates(true);
        return env.openDatabase(null, "publications_db", config);
    }

    private static DatabaseEntry key() {
        return new DatabaseEntry("10021333".getBytes(StandardCharsets.UTF_8));
    }

    private final class Fixture extends EntrezPublicationsRetriever
    {
        private final String title;
        private final AtomicInteger fetches;

        private Fixture(String title, AtomicInteger fetches, String output) {
            this.title = title;
            this.fetches = fetches;
            setOsAlias("os.bio-test");
            setOutputFile(cacheDirectory.resolve(output).toString());
        }

        @Override
        protected File getCacheDirectory() {
            return cacheDirectory.toFile();
        }

        @Override
        protected List<Publication> getPublications(ObjectStore os) {
            Publication publication = DynamicUtil.simpleCreateObject(Publication.class);
            publication.setPubMedId("10021333");
            return List.of(publication);
        }

        @Override
        protected Reader getReader(Set<Integer> ids) {
            fetches.incrementAndGet();
            return new StringReader("<eSummaryResult><DocSum><Id>10021333</Id>"
                    + "<Item Name=\"Title\" Type=\"String\">" + title
                    + "</Item></DocSum></eSummaryResult>");
        }
    }
}
