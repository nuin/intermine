package org.intermine.api.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.lucene.queryparser.classic.ParseException;
import org.intermine.api.InterMineAPITestCase;
import org.intermine.api.bag.BagManager;
import org.intermine.api.bag.ClassKeysNotFoundException;
import org.intermine.api.bag.UnknownBagTypeException;
import org.intermine.api.profile.BagState;
import org.intermine.api.profile.InterMineBag;
import org.intermine.api.profile.Profile;
import org.intermine.api.profile.ProfileManager;
import org.intermine.api.profile.SavedQuery;
import org.intermine.api.profile.TagManager;
import org.intermine.api.profile.TagManager.TagNameException;
import org.intermine.api.profile.TagManager.TagNamePermissionException;
import org.intermine.api.profile.TagManagerFactory;
import org.intermine.api.tag.TagNames;
import org.intermine.api.tag.TagTypes;
import org.intermine.api.template.ApiTemplate;
import org.intermine.metadata.FieldDescriptor;
import org.intermine.metadata.Model;
import org.intermine.model.testmodel.CEO;
import org.intermine.model.testmodel.Department;
import org.intermine.model.testmodel.Employee;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.ObjectStoreTestUtils;
import org.intermine.objectstore.ObjectStoreWriter;
import org.intermine.objectstore.ObjectStoreWriterFactory;
import org.intermine.pathquery.PathQuery;
import org.junit.Test;

public class SearchResultsTest extends InterMineAPITestCase {

    private ObjectStoreWriter storeDataWriter;

    private Profile bobProfile, sallyProfile;
    private ProfileManager pm;
    private final Integer bobId = new Integer(101);
    private final Integer sallyId = new Integer(102);
    private final String bobPass = "bob_pass";
    private final String sallyPass = "sally_pass";
    private Map<String, List<FieldDescriptor>>  classKeys;
    private final String bobKey = "BOBKEY";

    public SearchResultsTest(String arg) {
        super(arg);
    }

    public void setUp() throws Exception {
        super.setUp();
        classKeys = im.getClassKeys();
        pm = im.getProfileManager();

        storeDataWriter = ObjectStoreWriterFactory.getObjectStoreWriter("osw.unittest");
        Map data = ObjectStoreTestUtils.getTestData("testmodel", "testmodel_data.xml");
        ObjectStoreTestUtils.storeData(storeDataWriter, data);

        setUpUserProfiles();
    }

    public void tearDown() throws Exception {
        super.tearDown();

        ObjectStoreTestUtils.deleteAllObjectsInStore(storeDataWriter);
        if (storeDataWriter != null) {
            storeDataWriter.close();
        }

        SearchRepository.clearGlobalRepositories();
    }

    private void setUpUserProfiles() throws Exception {
        // Init global repo, as the API initialiser would.
        long time = System.currentTimeMillis();
        Profile su = pm.getSuperuserProfile();
        SearchRepository gsr = new GlobalRepository(su);

        PathQuery query = new PathQuery(Model.getInstanceByName("testmodel"));
        Date date = new Date();
        SavedQuery sq = new SavedQuery("query1", date, query);

        // bob's details
        String bobName = "bob";
        List<String> classKeys = new ArrayList<String>();
        classKeys.add("name");
        InterMineBag bag = new InterMineBag("bag1", "Department", "This is some description",
                new Date(), BagState.CURRENT, os, bobId, uosw, classKeys);

        Department deptEx = new Department();
        deptEx.setName("DepartmentA1");
        Set<String> fieldNames = new HashSet<String>();
        fieldNames.add("name");
        Department departmentA1 = os.getObjectByExample(deptEx, fieldNames);
        bag.addIdToBag(departmentA1.getId(), "Department");

        Department deptEx2 = new Department();
        deptEx2.setName("DepartmentB1");
        Department departmentB1 = os.getObjectByExample(deptEx2, fieldNames);
        bag.addIdToBag(departmentB1.getId(), "Department");

        ApiTemplate template =
            new ApiTemplate("template", "ttitle", "tcomment",
                              new PathQuery(Model.getInstanceByName("testmodel")));

        bobProfile = new Profile(pm, bobName, bobId, bobPass,
                Collections.EMPTY_MAP, Collections.EMPTY_MAP, Collections.EMPTY_MAP, bobKey,
                true, false);
        pm.createProfile(bobProfile);
        bobProfile.saveQuery("query1", sq);
        bobProfile.saveBag("bag1", bag);
        bobProfile.saveTemplate("template", template);

        query = new PathQuery(Model.getInstanceByName("testmodel"));
        sq = new SavedQuery("query1", date, query);

        // sally details
        String sallyName = "sally";

        // employees and managers
        //    <bag name="sally_bag2" type="org.intermine.model.CEO">
        //        <bagElement type="org.intermine.model.CEO" id="1011"/>
        //    </bag>

        CEO ceoEx = new CEO();
        ceoEx.setName("EmployeeB1");
        fieldNames = new HashSet<String>();
        fieldNames.add("name");
        CEO ceoB1 = os.getObjectByExample(ceoEx, fieldNames);
        Employee empA1Ex = new Employee();
        empA1Ex.setName("EmployeeA1");
        Employee empA1 = os.getObjectByExample(empA1Ex, fieldNames);

        InterMineBag objectBag = new InterMineBag("bag2", "Employee", "description including the "
                + "word 'orthologue'", new Date(), BagState.CURRENT, os, sallyId, uosw, classKeys);
        objectBag.addIdToBag(ceoB1.getId(), "CEO");
        InterMineBag objectBag2 = new InterMineBag("bag3", "Employee", "some funky description",
                new Date(), BagState.CURRENT, os, sallyId, uosw, classKeys);
        objectBag2.addIdToBag(ceoB1.getId(), "CEO");
        objectBag2.addIdToBag(empA1.getId(), "Employee");

        InterMineBag objectBag3 = new InterMineBag("bag4", "Employee", "some very funky description",
                new Date(), BagState.CURRENT, os, su.getUserId(), uosw, classKeys);
        objectBag3.addIdToBag(ceoB1.getId(), "CEO");
        objectBag3.addIdToBag(empA1.getId(), "Employee");

        InterMineBag nonGlobalBag = new InterMineBag("bag5", "Employee", "shh, its a secret",
                new Date(), BagState.CURRENT, os, su.getUserId(), uosw, classKeys);
        nonGlobalBag.addIdToBag(ceoB1.getId(), "CEO");
        nonGlobalBag.addIdToBag(empA1.getId(), "Employee");

        su.saveBag(objectBag3);
        su.saveBag(nonGlobalBag);

        template = new ApiTemplate("template", "ttitle", "tcomment",
                                     new PathQuery(Model.getInstanceByName("testmodel")));
        sallyProfile = new Profile(pm, sallyName, sallyId, sallyPass,
                  Collections.EMPTY_MAP, Collections.EMPTY_MAP, Collections.EMPTY_MAP, true, false);
        pm.createProfile(sallyProfile);
        sallyProfile.saveQuery("query1", sq);
        sallyProfile.saveBag("sally_bag1", objectBag);
        sallyProfile.saveBag("sally_bag2", objectBag2);

        sallyProfile.saveTemplate("template", template);

        ApiTemplate globalTemplate = new ApiTemplate("gtemplate", "gttitle", "gtcomment",
                new PathQuery(Model.getInstanceByName("testmodel")));
        su.saveTemplate(globalTemplate.getName(), globalTemplate);

        TagManager tm = im.getTagManager();
        tm.addTag("foo", objectBag, sallyProfile);
        tm.addTag("bar", objectBag, sallyProfile);
        tm.addTag("bar", objectBag2, sallyProfile);

        tm.addTag(TagNames.IM_PUBLIC, objectBag3, su);
        tm.addTag(TagNames.IM_PUBLIC, globalTemplate, su);
        tm.addTag("foo", globalTemplate, su);
        tm.addTag("foo", objectBag3, su);
        tm.addTag("foo", nonGlobalBag, su);

        System.out.printf("Spent %.4fs setting writing a mock user profile\n",
                Float.valueOf(System.currentTimeMillis() - time) / 1000);
    }

    @Test
    public void testRenameAfterSearchPreservesResultMetadata() throws Exception {
        ApiTemplate template = bobProfile.getSavedTemplates().get("template");
        for (String query : new String[] {"ttitle", ""}) {
            im.getTagManager().addTag("retained", template, bobProfile);
            SearchResults results = SearchResults.runLuceneSearch(query,
                    SearchTarget.USER_TEMPLATES, bobProfile.getSearchRepository());
            List<Object> before = results.iterator().next().asList();
            template.setName(template.getName() + "renamed");
            SearchResult result = results.iterator().next();
            assertSame(template, result.getItem());
            assertEquals(before.subList(1, 4), result.asList().subList(1, 4));
            assertTrue(((List<?>) result.asList().get(3)).contains("retained"));
        }
    }

    @Test
    public void testBlankSearchPrefersUserItemOnlyInAllScope() throws Exception {
        SearchRepository.clearGlobalRepositories();
        SearchRepository global = new GlobalRepository(pm.getSuperuserProfile());
        SearchRepository user = bobProfile.getSearchRepository();
        ApiTemplate publicItem = new ApiTemplate("collision", "public", "public description",
                new PathQuery(Model.getInstanceByName("testmodel"))) {
            @Override
            public int hashCode() {
                return 15;
            }
        };
        ApiTemplate privateItem = new ApiTemplate("collision", "private", "private description",
                new PathQuery(Model.getInstanceByName("testmodel"))) {
            @Override
            public int hashCode() {
                return 0;
            }
        };
        global.searchItems.clear();
        global.searchItems.add(publicItem);
        user.searchItems.clear();
        user.searchItems.add(privateItem);
        SearchResults all = SearchResults.runLuceneSearch("", SearchTarget.ALL_TEMPLATES, user);
        assertEquals(1, all.size());
        assertSame(privateItem, all.iterator().next().getItem());
        SearchResults published = SearchResults.runLuceneSearch("",
                SearchTarget.GLOBAL_TEMPLATES, user);
        assertEquals(1, published.size());
        assertSame(publicItem, published.iterator().next().getItem());
    }

    @Test
    public void testUnfilteredBagSearches() throws Exception {
        SearchTarget target = new SearchTarget(Scope.ALL, TagTypes.BAG);
        SearchResults results = SearchResults.runLuceneSearch("", target,
                sallyProfile.getSearchRepository());
        assertEquals(3, results.size());

        // Can only see own bags
        target = new SearchTarget(Scope.USER, TagTypes.BAG);
        results = SearchResults.runLuceneSearch("", target,
                sallyProfile.getSearchRepository());
        assertEquals(2, results.size());

        // And Bob can also see global bag
        results = SearchResults.runLuceneSearch("", target, bobProfile.getSearchRepository());
        assertEquals(2, results.size(), 1);

        // But not if restricting to the USER scope.
        target = new SearchTarget(Scope.USER, TagTypes.BAG);
        results = SearchResults.runLuceneSearch("", target, bobProfile.getSearchRepository());
        assertEquals(1, results.size());
    }

    @Test
    public void testUnfilteredTemplateSearches() throws Exception {
        SearchTarget target = new SearchTarget(Scope.ALL, TagTypes.TEMPLATE);
        SearchResults results = SearchResults.runLuceneSearch("", target,
                sallyProfile.getSearchRepository());
        assertEquals(results.size(), 2);

        target = new SearchTarget(Scope.USER, TagTypes.TEMPLATE);
        results = SearchResults.runLuceneSearch("", target,
                sallyProfile.getSearchRepository());
        assertEquals(results.size(), 1);
    }

    @Test
    public void testGlobalMapIncludesAllPublishers() throws Exception {
        bobProfile.setSuperuser(true);
        ApiTemplate template = bobProfile.getSavedTemplates().get("template");
        im.getTagManager().addTag(TagNames.IM_PUBLIC, template, bobProfile);
        new GlobalRepository(bobProfile);
        SearchRepository repository = SearchRepository.getGlobalSearchRepository(
                pm.getSuperuserProfile());
        assertSame(template, repository.getWebSearchableMap(TagTypes.TEMPLATE).get("template"));
        assertTrue(repository.getWebSearchableMap(TagTypes.TEMPLATE).containsKey("gtemplate"));
    }

    @Test
    public void testRenameDuringSearchKeepsIndexMappingConsistent() throws Exception {
        CountDownLatch indexed = new CountDownLatch(1);
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean(true);
        SearchRepository repository = new UserRepository(bobProfile) {
            @Override
            CachedIndex getIndex(String type) {
                CachedIndex index = super.getIndex(type);
                if (pause.compareAndSet(true, false)) {
                    indexed.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                }
                return index;
            }

            @Override
            public void receiveEvent(ChangeEvent event) {
                changed.countDown();
                super.receiveEvent(event);
            }
        };
        ApiTemplate template = bobProfile.getSavedTemplates().get("template");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SearchResults> search = executor.submit(() -> SearchResults.runLuceneSearch(
                    "ttitle", SearchTarget.USER_TEMPLATES, repository));
            assertTrue(indexed.await(10, TimeUnit.SECONDS));
            Future<?> rename = executor.submit(() -> template.setName("renamed"));
            assertTrue(changed.await(10, TimeUnit.SECONDS));
            release.countDown();
            assertSame(template, search.get(10, TimeUnit.SECONDS).iterator().next().getItem());
            rename.get(10, TimeUnit.SECONDS);
            SearchResults current = SearchResults.runLuceneSearch("ttitle",
                    SearchTarget.USER_TEMPLATES, repository);
            assertEquals(1, current.size());
            assertEquals("renamed", current.iterator().next().getItem().getName());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testChangeEventDoesNotWaitForTagLookup() throws Exception {
        CountDownLatch tagLockHeld = new CountDownLatch(1);
        CountDownLatch indexing = new CountDownLatch(1);
        CountDownLatch releaseTags = new CountDownLatch(1);
        AtomicBoolean watching = new AtomicBoolean(false);
        SearchRepository repository = bobProfile.getSearchRepository();
        ApiTemplate template = new ApiTemplate("concurrent", "needle", "description",
                new PathQuery(Model.getInstanceByName("testmodel"))) {
            @Override
            public String getTitle() {
                if (watching.get()) {
                    indexing.countDown();
                }
                return super.getTitle();
            }
        };
        repository.searchItems.clear();
        repository.searchItems.add(template);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> tags = executor.submit(() -> {
                synchronized (im.getTagManager()) {
                    tagLockHeld.countDown();
                    assertTrue(releaseTags.await(10, TimeUnit.SECONDS));
                }
                return null;
            });
            assertTrue(tagLockHeld.await(10, TimeUnit.SECONDS));
            watching.set(true);
            Future<SearchResults> search = executor.submit(() -> SearchResults.runLuceneSearch(
                    "needle", SearchTarget.USER_TEMPLATES, repository));
            assertTrue(indexing.await(10, TimeUnit.SECONDS));
            Future<?> event = executor.submit(() -> repository.receiveEvent(
                    new PropertyChangeEvent(template)));
            event.get(2, TimeUnit.SECONDS);
            releaseTags.countDown();
            tags.get(10, TimeUnit.SECONDS);
            assertSame(template, search.get(10, TimeUnit.SECONDS).iterator().next().getItem());
        } finally {
            releaseTags.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testMoreThanOneThousandMatches() throws Exception {
        SearchRepository repository = bobProfile.getSearchRepository();
        for (int i = 0; i < 1001; i++) {
            repository.searchItems.add(new ApiTemplate("bulk" + i, "migrationneedle", "entry",
                    new PathQuery(Model.getInstanceByName("testmodel"))));
        }
        SearchResults results = SearchResults.runLuceneSearch("migrationneedle",
                SearchTarget.USER_TEMPLATES, repository);
        assertEquals(1000, results.size());
        for (SearchResult result : results) {
            assertTrue(result.getItem().getName().startsWith("bulk"));
        }
    }

    @Test
    public void testGlobalSearchDoesNotResolvePrivateNameCollision() throws Exception {
        InterMineBag publicBag = pm.getSuperuserProfile().getSavedBags().get("bag4");
        InterMineBag privateBag = new InterMineBag("bag4", "Employee", "private content",
                new Date(), BagState.CURRENT, os, bobId, uosw, Collections.singletonList("name"));
        bobProfile.saveBag(privateBag);
        SearchResults results = SearchResults.runLuceneSearch("funky", SearchTarget.GLOBAL_BAGS,
                bobProfile.getSearchRepository());
        assertEquals(1, results.size());
        assertSame(publicBag, results.iterator().next().getItem());
    }

    @Test
    public void testGlobalSearchWithoutRepositories() throws Exception {
        SearchRepository.clearGlobalRepositories();
        SearchResults results = SearchResults.runLuceneSearch("description",
                SearchTarget.GLOBAL_BAGS, bobProfile.getSearchRepository());
        assertEquals(0, results.size());
    }

    @Test
    public void testFilteredSearchHighlightsDescriptionsAndRetainsTags() throws Exception {
        SearchResults results = SearchResults.runLuceneSearch("ortholog", SearchTarget.USER_BAGS,
                sallyProfile.getSearchRepository());
        assertEquals(1, results.size());
        List<Object> result = results.iterator().next().asList();
        assertTrue(result.get(1).toString().contains(
                "<span class=\"im-highlighted-search-term\">orthologue</span>"));
        assertTrue(((List<?>) result.get(3)).contains("foo"));
        assertTrue(((List<?>) result.get(3)).contains("bar"));
    }

    @Test
    public void testFilteredBagSearches() throws Exception {
        SearchResults results;
        SearchRepository sr = sallyProfile.getSearchRepository();

        results = SearchResults.runLuceneSearch("funk", SearchTarget.ALL_BAGS, sr);
        assertEquals(2, results.size());

        results = SearchResults.runLuceneSearch("funk", SearchTarget.USER_BAGS, sr);
        assertEquals(1, results.size());

        results = SearchResults.runLuceneSearch("ortholog", SearchTarget.ALL_BAGS, sr);

        assertEquals(1, results.size());

        results = SearchResults.runLuceneSearch("tags:foo OR tags:bar", SearchTarget.ALL_BAGS, sr);

        assertEquals(3, results.size());

    }

    @Test
    public void testRespondToTaggingChanges() throws ParseException, IOException, TagNameException, TagNamePermissionException {
        SearchResults results;

        TagManager       tm = im.getTagManager();
        BagManager       bm = im.getBagManager();
        Profile          su = pm.getSuperuserProfile();
        SearchRepository sr = sallyProfile.getSearchRepository();

        results = SearchResults.runLuceneSearch("tags:foo OR tags:bar", SearchTarget.ALL_BAGS, sr);
        assertEquals(3, results.size());

        tm.addTag(TagNames.IM_PUBLIC, bm.getUserBag(su, "bag5"), su);

        results = SearchResults.runLuceneSearch("tags:foo OR tags:bar", SearchTarget.ALL_BAGS, sr);
        assertEquals(4, results.size());

        tm.deleteTag(TagNames.IM_PUBLIC, bm.getUserBag(su, "bag5"), su);

        results = SearchResults.runLuceneSearch("tags:foo OR tags:bar", SearchTarget.ALL_BAGS, sr);
        assertEquals(3, results.size());
    }

    @Test
    public void testRespondToDeletion() throws ParseException, IOException, TagNameException, TagNamePermissionException, ObjectStoreException {
        SearchResults results;
        SearchRepository sr = sallyProfile.getSearchRepository();

        results = SearchResults.runLuceneSearch("tags:foo OR tags:bar", SearchTarget.ALL_BAGS, sr);
        assertEquals(3, results.size());

        sallyProfile.deleteBag("sally_bag1");

        results = SearchResults.runLuceneSearch("tags:foo OR tags:bar", SearchTarget.ALL_BAGS, sr);
        assertEquals(2, results.size());
    }

    @Test
    public void testRespondToCreation() throws ParseException, IOException, TagNameException,
        TagNamePermissionException, ObjectStoreException, UnknownBagTypeException,
        ClassKeysNotFoundException {
        SearchResults results;
        SearchRepository sr = sallyProfile.getSearchRepository();

        results = SearchResults.runLuceneSearch("funk", SearchTarget.USER_BAGS, sr);
        assertEquals(1, results.size());

        InterMineBag newBag = sallyProfile.createBag("totallyNewBag", "Employee", "the funkiest bag", Collections.EMPTY_MAP);

        results = SearchResults.runLuceneSearch("funk", SearchTarget.USER_BAGS, sr);
        assertEquals(2, results.size());
    }

    @Test
    public void testRespondToPropertyChanges() throws ParseException, IOException, ObjectStoreException {
        SearchResults results;
        SearchRepository sr = sallyProfile.getSearchRepository();

        results = SearchResults.runLuceneSearch("funk", SearchTarget.ALL_BAGS, sr);
        assertEquals(2, results.size());

        BagManager bm = im.getBagManager();
        bm.getUserBag(sallyProfile, "sally_bag2").setDescription("not so easy to find now!");

        results = SearchResults.runLuceneSearch("easy", SearchTarget.ALL_BAGS, sr);
        assertEquals(1, results.size());

        results = SearchResults.runLuceneSearch("funk", SearchTarget.ALL_BAGS, sr);
        assertEquals(1, results.size());

        for (SearchResult r: results) {
            ((InterMineBag) r.getItem()).setDescription("That was easy");
        }

        results = SearchResults.runLuceneSearch("easy", SearchTarget.ALL_BAGS, sr);
        assertEquals(2, results.size());
    }
}
