package org.intermine.web.logic.profile;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.intermine.api.InterMineAPITestCase;
import org.intermine.api.profile.BagState;
import org.intermine.api.profile.InterMineBag;
import org.intermine.api.profile.Profile;
import org.intermine.api.profile.ProfileManager;
import org.intermine.api.profile.SavedQuery;
import org.intermine.model.testmodel.Employee;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.ObjectStoreWriter;
import org.intermine.pathquery.PathQuery;

public class AnonymousProfileMergerTest extends InterMineAPITestCase {
    private ProfileManager manager;
    private Profile source;
    private Profile destination;
    private Integer employeeId;

    public AnonymousProfileMergerTest(String name) {
        super(name);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        manager = im.getProfileManager();
        source = manager.createAnonymousProfile();
        destination = manager.createNewProfile("destination", "password");
        ObjectStoreWriter writer = os.getNewWriter();
        try {
            Employee employee = new Employee();
            employee.setName("Transfer employee");
            writer.store(employee);
            employeeId = employee.getId();
        } finally {
            writer.close();
        }
    }

    private InterMineBag addList(Profile profile, String name) throws Exception {
        InterMineBag bag = new InterMineBag(name, "Employee", "Original description",
                new Date(1700000000000L), BagState.CURRENT, os, profile.getUserId(), uosw,
                Collections.singletonList("name"));
        bag.addIdToBag(employeeId, "Employee");
        profile.saveBag(bag);
        return bag;
    }

    public void testNullSourceIsNoOp() {
        assertFalse(LoginHandler.mergeProfiles(null, destination).hasIssues());
        assertTrue(destination.getSavedBags().isEmpty());
    }

    public void testNamedSourceCannotTransferPrivateData() throws Exception {
        Profile named = manager.createNewProfile("named-source", "password");
        InterMineBag original = addList(named, "private");
        LoginHandler.mergeProfiles(named, destination);
        assertTrue(destination.getSavedBags().isEmpty());
        assertSame(original, named.getSavedBags().get("private"));
        assertEquals(named.getUserId(), original.getProfileId());
    }

    public void testAnonymousDestinationIsRejected() throws Exception {
        addList(source, "guest");
        try {
            LoginHandler.mergeProfiles(source, manager.createAnonymousProfile());
            fail("An anonymous destination cannot take ownership");
        } catch (IllegalArgumentException expected) {
            assertEquals(1, source.getSavedBags().size());
        }
    }

    public void testTransferCreatesIndependentCopyAndPreservesMetadata() throws Exception {
        InterMineBag original = addList(source, "guest");
        PathQuery query = new PathQuery(im.getModel());
        query.addView("Employee.name");
        SavedQuery history = new SavedQuery("history", new Date(), query);
        source.saveHistory(history);
        LoginHandler.mergeProfiles(source, destination);
        InterMineBag copy = destination.getSavedBags().get("guest");
        assertNotSame(original, copy);
        assertEquals("Employee", copy.getType());
        assertEquals("Original description", copy.getDescription());
        assertEquals(new Date(1700000000000L), copy.getDateCreated());
        assertEquals(BagState.CURRENT.toString(), copy.getState());
        assertEquals(Collections.singletonList("name"), copy.getKeyFieldNames());
        assertEquals(Collections.singletonList(employeeId), copy.getContentsAsIds());
        assertEquals(destination.getUserId(), copy.getProfileId());
        assertNull(original.getProfileId());
        assertTrue(source.getSavedBags().isEmpty());
        assertTrue(destination.getHistory().containsValue(history));
    }

    public void testCollisionPreservesBothListsAndReportsName() throws Exception {
        InterMineBag existing = addList(destination, "guest");
        InterMineBag original = addList(source, "guest");
        ProfileMergeIssues issues = LoginHandler.mergeProfiles(source, destination);
        String newName = issues.getRenamedBags().get("guest");
        assertEquals("guest_1", newName);
        assertSame(existing, destination.getSavedBags().get("guest"));
        assertNotSame(original, destination.getSavedBags().get(newName));
        assertEquals("guest", original.getName());
        assertEquals(2, destination.getSavedBags().size());
        assertTrue(source.getSavedBags().isEmpty());
    }

    public void testConcurrentClaimsHaveOneOwner() throws Exception {
        addList(source, "guest");
        Profile second = manager.createNewProfile("second", "password");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = workers.submit(() -> {
                start.await();
                return LoginHandler.mergeProfiles(source, destination);
            });
            Future<?> other = workers.submit(() -> {
                start.await();
                return LoginHandler.mergeProfiles(source, second);
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            other.get(10, TimeUnit.SECONDS);
            assertEquals(1, destination.getSavedBags().size() + second.getSavedBags().size());
            assertTrue(source.getSavedBags().isEmpty());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    public void testSourceRemovalFailureKeepsCopyAndCause() throws Exception {
        ObjectStoreException failure = new ObjectStoreException("injected removal failure");
        Profile failingSource = new Profile(manager, null, null, null, Profile.NO_QUERIES,
                new HashMap<String, InterMineBag>(), Profile.NO_TEMPLATES, null, true, false) {
            @Override
            public void deleteBag(String name) throws ObjectStoreException {
                throw failure;
            }
        };
        InterMineBag original = addList(failingSource, "guest");
        try {
            LoginHandler.mergeProfiles(failingSource, destination);
            fail("A failed removal must not report success");
        } catch (IllegalStateException expected) {
            assertSame(failure, expected.getCause());
        }
        assertSame(original, failingSource.getSavedBags().get("guest"));
        InterMineBag copy = destination.getSavedBags().get("guest");
        assertNotSame(original, copy);
        assertEquals(Collections.singletonList(employeeId), copy.getContentsAsIds());
    }

    public void testCopyReadFailureKeepsSource() throws Exception {
        IllegalStateException failure = new IllegalStateException("injected read failure");
        InterMineBag unreadable = new InterMineBag("guest", "Employee", "description",
                new Date(), BagState.CURRENT, os, null, uosw, Collections.singletonList("name")) {
            @Override
            public List<Integer> getContentsAsIds() {
                throw failure;
            }
        };
        source.saveBag(unreadable);
        try {
            LoginHandler.mergeProfiles(source, destination);
            fail("A failed copy must not report success");
        } catch (IllegalStateException expected) {
            assertSame(failure, expected);
        }
        assertSame(unreadable, source.getSavedBags().get("guest"));
        assertNull(unreadable.getProfileId());
        assertTrue(destination.getSavedBags().isEmpty());
    }

    public void testSharedTransferGuards() throws Exception {
        assertFalse(AnonymousProfileMerger.transferBags(null, destination).hasIssues());
        addList(destination, "private");
        assertFalse(AnonymousProfileMerger.transferBags(destination, source).hasIssues());
        assertTrue(source.getSavedBags().isEmpty());
        for (Profile invalid : new Profile[] {null, source}) {
            try {
                AnonymousProfileMerger.transferBags(source, invalid);
                fail("A transfer needs a different named destination");
            } catch (IllegalArgumentException expected) {
                assertTrue(source.getSavedBags().isEmpty());
            }
        }
    }

    public void testTwoAnonymousSourcesUseUniqueDestinationNames() throws Exception {
        Profile secondSource = manager.createAnonymousProfile();
        addList(source, "guest");
        addList(secondSource, "guest");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = workers.submit(() -> {
                start.await();
                return AnonymousProfileMerger.transferBags(source, destination);
            });
            Future<?> second = workers.submit(() -> {
                start.await();
                return AnonymousProfileMerger.transferBags(secondSource, destination);
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertEquals(2, destination.getSavedBags().size());
            for (String name : new String[] {"guest", "guest_1"}) {
                assertEquals(Collections.singletonList(employeeId),
                        destination.getSavedBags().get(name).getContentsAsIds());
            }
            assertTrue(source.getSavedBags().isEmpty());
            assertTrue(secondSource.getSavedBags().isEmpty());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
