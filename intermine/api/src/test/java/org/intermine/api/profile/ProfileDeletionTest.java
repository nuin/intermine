package org.intermine.api.profile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.intermine.api.InterMineAPITestCase;

public class ProfileDeletionTest extends InterMineAPITestCase {
    public ProfileDeletionTest(String name) {
        super(name);
    }

    public void testConcurrentDeletionKeepsTransactionsIndependent()
        throws ExecutionException, InterruptedException, TimeoutException {
        ProfileManager manager = im.getProfileManager();
        Profile first = manager.createBasicLocalProfile("deletion-one", "password", null);
        Profile second = manager.createBasicLocalProfile("deletion-two", "password", null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> deletions = new ArrayList<Future<?>>();
        try {
            for (Profile profile : Arrays.asList(first, second)) {
                deletions.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    manager.deleteProfile(profile);
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> deletion : deletions) {
                deletion.get(30, TimeUnit.SECONDS);
            }
            assertNull(manager.getUserProfile(first.getUserId()));
            assertNull(manager.getUserProfile(second.getUserId()));
            assertNotNull(manager.getUserProfile(testUser.getUserId()));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
