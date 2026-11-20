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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import junit.framework.TestCase;

import org.intermine.sql.Database;
import org.intermine.sql.DatabaseFactory;

public class MetadataManagerConnectionTest extends TestCase
{
    private static final String KEY = "metadata_connection_failure";

    public void testWriteErrorSurvivesRollbackFailure() throws Exception {
        for (boolean binary : new boolean[] {false, true}) {
            try (ConnectionProbe probe = new ConnectionProbe("rollback")) {
                try {
                    store(probe, binary);
                    fail("Expected rejected write");
                } catch (SQLException error) {
                    assertSame(probe.writeFailure, error);
                    assertEquals(1, error.getSuppressed().length);
                    assertSame(probe.rollbackFailure, error.getSuppressed()[0]);
                }
                assertTrue("Connection must close after rollback failure", probe.closed);
            }
        }
    }

    public void testStatementCloseFailureClosesConnection() throws Exception {
        checkCleanupFailure("statementClose");
    }

    public void testAutoCommitRestoreFailureClosesConnection() throws Exception {
        checkCleanupFailure("restore");
    }

    private void checkCleanupFailure(String failure) throws Exception {
        for (boolean binary : new boolean[] {false, true}) {
            try (ConnectionProbe probe = new ConnectionProbe(failure)) {
                try {
                    store(probe, binary);
                    fail("Expected cleanup failure");
                } catch (SQLException error) {
                    assertEquals(failure, error.getMessage());
                }
                assertTrue("Connection must close when cleanup fails", probe.closed);
            }
        }
    }

    public void testMalformedLargeObjectReferenceClosesConnection() throws Exception {
        Database database = DatabaseFactory.getDatabase("db.unittest");
        MetadataManager.store(database, KEY, "BLOB: invalid");
        try {
            for (String operation : new String[] {"read", "write", "delete"}) {
                try (ConnectionProbe probe = new ConnectionProbe("none")) {
                    try {
                        if ("read".equals(operation)) {
                            MetadataManager.readLargeBinary(probe, KEY);
                        } else if ("write".equals(operation)) {
                            MetadataManager.storeLargeBinary(probe, KEY);
                        } else {
                            MetadataManager.deleteLargeBinary(probe, KEY);
                        }
                        fail("Expected invalid large-object reference");
                    } catch (SQLException error) {
                        assertTrue(error.getCause() instanceof NumberFormatException);
                    }
                    assertTrue(operation + " must release its connection", probe.closed);
                }
            }
        } finally {
            MetadataManager.store(database, KEY, null);
        }
    }

    private void store(Database database, boolean binary) throws SQLException {
        if (binary) {
            MetadataManager.storeBinary(database, KEY, new byte[] {1});
        } else {
            MetadataManager.store(database, KEY, "value");
        }
    }

    private static final class ConnectionProbe extends Database implements AutoCloseable
    {
        private final Connection actual;
        private final Connection tracked;
        private final String failure;
        private final SQLException writeFailure = new SQLException("write rejected");
        private final SQLException rollbackFailure = new SQLException("rollback failed");
        private boolean closed;

        ConnectionProbe(String failure) throws Exception {
            this.failure = failure;
            actual = DatabaseFactory.getDatabase("db.unittest").getConnection();
            tracked = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, arguments) ->
                            invokeConnection(method, arguments));
        }

        @Override
        public Connection getConnection() {
            return tracked;
        }

        private Object invokeConnection(Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            if ("prepareStatement".equals(name) && "rollback".equals(failure)) {
                throw writeFailure;
            }
            if ("close".equals(name)) {
                closed = true;
            }
            Object result = invoke(actual, method, arguments);
            if ("rollback".equals(name) && "rollback".equals(failure)) {
                throw rollbackFailure;
            }
            if ("setAutoCommit".equals(name) && Boolean.TRUE.equals(arguments[0])
                    && "restore".equals(failure)) {
                throw new SQLException(failure);
            }
            if ("prepareStatement".equals(name) && "statementClose".equals(failure)) {
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                        new Class<?>[] {PreparedStatement.class}, (proxy, call, args) -> {
                            Object value = invoke(result, call, args);
                            if ("close".equals(call.getName())) {
                                throw new SQLException(failure);
                            }
                            return value;
                        });
            }
            return result;
        }

        private Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }

        @Override
        public void close() throws SQLException {
            actual.close();
        }
    }
}
