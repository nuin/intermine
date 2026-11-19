package org.intermine.dataloader;

/*
 * Copyright (C) 2002-2022 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.sql.Connection;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import junit.framework.TestCase;

import org.intermine.sql.Database;
import org.intermine.sql.DatabaseFactory;
import org.intermine.sql.DatabaseConnectionException;

public class DataTrackingTest extends TestCase {
    protected DataTracker dt;
    protected Source source1, source2;

    public void setUp() throws Exception {
        dt = new DataTracker(DatabaseFactory.getDatabase("db.unittest"), 30, 10);
        source1 = dt.stringToSource("Source1");
        source2 = dt.stringToSource("Source2");
    }

    public void tearDown() throws Exception {
        try {
            dt.close();
            Database db = DatabaseFactory.getDatabase("db.unittest");
            Connection c = db.getConnection();
            c.setAutoCommit(true);
            c.createStatement().execute("DROP TABLE tracker");
            c.close();
        } catch (Exception e) {
        }
    }

    public void testSetSourceNullIds() throws Exception {
        try {
            dt.setSource(null, "name", source1);
            fail("Expected NullPointerException");
        } catch (NullPointerException e) {
        }

        try {
            dt.setSource(new Integer(46), "name", new Source("dummy"));
            fail("Expected NullPointerException");
        } catch (NullPointerException e) {
        }

        try {
            dt.setSource(new Integer(46), null, source1);
            fail("Expected NullPointerException");
        } catch (NullPointerException e) {
        }
    }

    public void testGetSource() throws Exception {
        dt.setSource(new Integer(13), "name", source1);
        assertEquals(source1.getName(), dt.getSource(new Integer(13), "name").getName());

        for (int i = 100; i < 200; i++) {
            dt.setSource(new Integer(i), "name", source2);
        }

        assertEquals(source1.getName(), dt.getSource(new Integer(13), "name").getName());
    }

    public void testUnicodeSourceNamesPersist() throws Exception {
        String[] names = {
            "vigun.iSelect-consensus-2016.gen.Mu\u00f1oz-Amatria\u00edn_Mirebrahim_2017",
            "\u57fa\u56e0\u6570\u636e", "source-\ud83e\uddec", "ASCII-source"
        };
        for (int i = 0; i < names.length; i++) {
            dt.setSource(i + 100, "name", dt.stringToSource(names[i]));
        }
        dt.close();
        dt = new DataTracker(DatabaseFactory.getDatabase("db.unittest"), 30, 10);
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], dt.getSource(i + 100, "name").getName());
        }
    }

    public void testUnicodeFieldNamesPersist() throws Exception {
        String[] fields = {"na\u00efve", "\u540d\u79f0", "field-\ud83e\uddec"};
        for (String field : fields) {
            dt.setSource(101, field, source1);
        }
        dt.close();
        dt = new DataTracker(DatabaseFactory.getDatabase("db.unittest"), 30, 10);
        for (String field : fields) {
            assertEquals("Source1", dt.getSource(101, field).getName());
        }
    }

    public void testPortableWriterPreservesQuotedNames() throws Exception {
        dt.close();
        final Database database = DatabaseFactory.getDatabase("db.unittest");
        Database portableDatabase = new Database() {
            @Override
            public Connection getConnection() throws DatabaseConnectionException {
                final Connection connection = database.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if ("isWrapperFor".equals(method.getName())) {
                            return false;
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            }
        };
        dt = new DataTracker(portableDatabase, 30, 10);
        String name = "O'Brien-\u57fa\u56e0";
        dt.setSource(102, "field'name", dt.stringToSource(name));
        dt.close();
        dt = new DataTracker(database, 30, 10);
        assertEquals(name, dt.getSource(102, "field'name").getName());
    }
    
    // This is to investigate a possible bug where the version numbers are initialised from zero
    // for each data source, instead of continuing.
    public void testWrongOrderBug() throws Exception {
        dt.setSource(new Integer(13), "name", source1);
        dt.flush();
        dt.setSource(new Integer(14), "name", source1);
        dt.flush();
        DataTracker dt2 = new DataTracker(DatabaseFactory.getDatabase("db.unittest"), 30, 10);
        dt2.setSource(new Integer(14), "name", dt2.stringToSource("Source2"));
        dt2.close();
        dt2 = new DataTracker(DatabaseFactory.getDatabase("db.unittest"), 30, 10);
        assertEquals(source2.getName(), dt2.getSource(new Integer(14), "name").getName());
    }
}
