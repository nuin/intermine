package org.intermine.objectstore.intermine;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.intermine.objectstore.ObjectStoreWriterFactory;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObjectStoreWriterInterruptionTest
{
    @Test
    public void interruptedWaitDoesNotAcquireConnection() throws Exception {
        ObjectStoreWriterInterMineImpl writer = (ObjectStoreWriterInterMineImpl)
                ObjectStoreWriterFactory.getObjectStoreWriter("osw.unittest");
        Connection held = writer.getConnection();
        AtomicReference<SQLException> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                Connection acquired = writer.getConnection();
                writer.releaseConnection(acquired);
            } catch (SQLException e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        boolean stillWaiting;
        try {
            waiter.start();
            waiter.join(1000);
            stillWaiting = waiter.isAlive();
        } finally {
            writer.releaseConnection(held);
            waiter.join(5000);
            writer.close();
        }
        assertFalse("Interrupted caller remained blocked", stillWaiting);
        assertTrue("Interruption must be reported", failure.get() != null);
        assertTrue(failure.get().getCause() instanceof InterruptedException);
        assertTrue("Interrupt status must be preserved", interrupted.get());
    }
}
