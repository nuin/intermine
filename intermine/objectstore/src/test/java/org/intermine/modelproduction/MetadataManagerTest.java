package org.intermine.modelproduction;

/*
 * Copyright (C) 2002-2022 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import junit.framework.TestCase;

import org.intermine.sql.Database;
import org.intermine.sql.DatabaseFactory;

public class MetadataManagerTest extends TestCase
{
    private Database db;

    @Override
    public void setUp() throws Exception {
        db = DatabaseFactory.getDatabase("db.unittest");
    }

    public void testValue() throws Exception {
        MetadataManager.store(db, "test_key", "Hello");
        assertEquals("Hello", MetadataManager.retrieve(db, "test_key"));
    }

    public void testStoreReportsFailureAndPreservesPreviousValue() throws Exception {
        String key = "metadata_failure_test";
        MetadataManager.store(db, key, "original");
        try (Connection connection = db.getConnection();
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute("ALTER TABLE intermine_metadata ADD CONSTRAINT "
                    + "reject_test_metadata CHECK (value <> 'rejected_metadata_value')");
            try {
                try {
                    MetadataManager.store(db, key, "rejected_metadata_value");
                    fail("A rejected metadata write must report its database error");
                } catch (SQLException e) {
                    assertEquals("23514", e.getSQLState());
                }
                assertEquals("original", MetadataManager.retrieve(db, key));
            } finally {
                statement.execute("ALTER TABLE intermine_metadata "
                        + "DROP CONSTRAINT reject_test_metadata");
                MetadataManager.store(db, key, null);
            }
        }
    }

    public void testBinaryValue() throws Exception {
        byte[] expected = "Hello".getBytes();
        MetadataManager.storeBinary(db, "test_key_bin", expected);
        try (InputStream is = MetadataManager.retrieveBLOBInputStream(db, "test_key_bin")) {
            byte[] got = new byte[expected.length + 1];
            assertEquals(expected.length, is.read(got));
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], got[i]);
            }
        }
    }

    public void testBinaryStoreReportsFailureAndPreservesPreviousValue() throws Exception {
        String key = "binary_failure_test";
        MetadataManager.storeBinary(db, key, new byte[] {10, 20});
        try (Connection connection = db.getConnection();
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute("ALTER TABLE intermine_metadata ADD CONSTRAINT "
                    + "reject_test_binary CHECK (key <> 'binary_failure_test' "
                    + "OR octet_length(blob_value) <> 1)");
            try {
                try {
                    MetadataManager.storeBinary(db, key, new byte[] {30});
                    fail("A rejected binary metadata write must report its database error");
                } catch (SQLException e) {
                    assertEquals("23514", e.getSQLState());
                }
                try (InputStream value = MetadataManager.retrieveBLOBInputStream(db, key)) {
                    assertEquals(10, value.read());
                    assertEquals(20, value.read());
                    assertEquals(-1, value.read());
                }
            } finally {
                statement.execute("ALTER TABLE intermine_metadata "
                        + "DROP CONSTRAINT reject_test_binary");
                MetadataManager.store(db, key, null);
            }
        }
    }

    public void testLargeValue() throws Exception {
        byte[] expected = "Hello".getBytes();
        try (OutputStream os = MetadataManager.storeLargeBinary(db, "test_key_large")) {
            os.write(expected);
        }
        try (InputStream is = MetadataManager.readLargeBinary(db, "test_key_large")) {
            byte[] got = new byte[expected.length + 1];
            assertEquals(expected.length, is.read(got));
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], got[i]);
            }
        }
    }

    public void testLargeValueUsesLiteralQuotedKey() throws Exception {
        String key = "quoted'large_metadata";
        MetadataManager.store(db, "large_metadata_control", "preserve");
        try (OutputStream value = MetadataManager.storeLargeBinary(db, key)) {
            value.write(new byte[] {10, 20});
        }
        try (InputStream value = MetadataManager.readLargeBinary(db, key)) {
            assertEquals(10, value.read());
            assertEquals(20, value.read());
            assertEquals(-1, value.read());
        }
        try (OutputStream value = MetadataManager.storeLargeBinary(db, key)) {
            value.write(30);
        }
        try (InputStream value = MetadataManager.readLargeBinary(db, key)) {
            assertEquals(30, value.read());
            assertEquals(-1, value.read());
        }
        assertTrue(MetadataManager.deleteLargeBinary(db, key));
        assertNull(MetadataManager.readLargeBinary(db, key));
        assertEquals("preserve", MetadataManager.retrieve(db, "large_metadata_control"));
        MetadataManager.store(db, "large_metadata_control", null);
    }

    public void testDeleteLargeBinary() throws Exception {
        byte[] expected = "Hello".getBytes();
        String key = "test_key_large";

        try (OutputStream os = MetadataManager.storeLargeBinary(db, key)) {
            os.write(expected);
        }
        assertNotNull(MetadataManager.retrieve(db, key));
        MetadataManager.deleteLargeBinary(db, key);
        assertNull(MetadataManager.retrieve(db, key));

    }
}
