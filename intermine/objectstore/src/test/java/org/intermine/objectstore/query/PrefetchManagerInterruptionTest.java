package org.intermine.objectstore.query;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.intermine.objectstore.ObjectStoreException;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PrefetchManagerInterruptionTest
{
    @Test
    public void interruptedWaitLeavesSharedRequestRunning() throws Exception {
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        ResultsBatches result = new ResultsBatches(new Query(), null, Collections.emptyMap()) {
            @Override
            protected List<Object> fetchBatchFromObjectStore(int batchNo, boolean optimise,
                    boolean explain) throws ObjectStoreException {
                fetches.incrementAndGet();
                fetching.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new ObjectStoreException("Test fetch was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ObjectStoreException("Test fetch was interrupted", e);
                }
                List<Object> batch = Collections.emptyList();
                batches.put(batchNo, batch);
                return batch;
            }
        };
        AtomicReference<Throwable> fetchFailure = new AtomicReference<>();
        Thread fetcher = new Thread(() -> {
            try {
                PrefetchManager.doRequest(result, 0, false, false);
            } catch (ObjectStoreException e) {
                fetchFailure.set(e);
            }
        });
        AtomicReference<ObjectStoreException> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                PrefetchManager.doRequest(result, 0, false, false);
            } catch (ObjectStoreException e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        boolean stillWaiting;
        boolean stillServiced;
        try {
            fetcher.start();
            assertTrue(fetching.await(5, TimeUnit.SECONDS));
            waiter.start();
            waiter.join(1000);
            stillWaiting = waiter.isAlive();
            stillServiced = fetcher.isAlive();
        } finally {
            release.countDown();
            fetcher.join(5000);
            waiter.join(5000);
        }
        assertFalse("Interrupted caller remained blocked", stillWaiting);
        assertTrue("Other consumers still need the shared fetch", stillServiced);
        assertTrue("Shared fetch must complete", fetchFailure.get() == null);
        assertTrue("Shared batch must be fetched once", fetches.get() == 1);
        assertTrue("Interruption must be reported", failure.get() != null);
        assertTrue(failure.get().getCause() instanceof InterruptedException);
        assertTrue("Interrupt status must be preserved", interrupted.get());
    }
}
