package org.intermine.api.search;

/*
 * Copyright (C) 2002-2022 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.intermine.api.profile.Profile;
import org.intermine.api.profile.TagManager;
import org.intermine.api.profile.TagManagerFactory;
import org.intermine.api.tag.AspectTagUtil;
import org.intermine.api.userprofile.Tag;

/**
 * The base class for search repositories. Classes that extend this base must provide
 * implementations that define the responses to change events that affect the web searchables this
 * repository is possibly interested in.
 *
 * @author Alex Kalderimis
 *
 */
public abstract class SearchRepository implements WebSearchWatcher
{

    protected static final Set<SearchRepository> GLOBALS =
            new CopyOnWriteArraySet<SearchRepository>();

    private static final Logger LOG = Logger.getLogger(SearchRepository.class);

    protected Set<WebSearchable> searchItems = new CopyOnWriteArraySet<WebSearchable>();
    private final Map<String, CachedIndex> indexes = new HashMap<String, CachedIndex>();
    private long indexVersion;

    /** An index and the object identities captured in its documents. */
    static final class CachedIndex
    {
        final Directory directory;
        final Map<Integer, WebSearchable> items;

        private CachedIndex(Directory directory, Map<Integer, WebSearchable> items) {
            this.directory = directory;
            this.items = Collections.unmodifiableMap(items);
        }
    }

    protected final Profile profile;
    protected final TagManager tagManager;
    private final String indexId = UUID.randomUUID().toString();

    /** @return the identifier that associates index documents with this repository */
    String getIndexId() {
        return indexId;
    }

    /**
     * Constructor. Create a new search repository for the given profile.
     * @param profile The gateway to the user data we want to search.
     */
    public SearchRepository(Profile profile) {
        this.profile = profile;
        this.tagManager = new TagManagerFactory(profile.getProfileManager()).getTagManager();
        populateSearchItems();
        startListening();
    }

    /**
     * Begin listening for events on the items of interest.
     */
    protected void startListening() {
        for (WebSearchable ws: searchItems) {
            ws.addObserver(this);
        }
    }

    /** Stop listening for events **/
    protected void stopListening() {
        for (WebSearchable ws: searchItems) {
            ws.removeObserver(this);
        }
    }

    /**
     * Get the search repositories registered as global repositories.
     * @return An unmodifiable set of global search repositories.
     */
    public static Set<SearchRepository> getGlobalSearchRepositories() {
        return Collections.unmodifiableSet(GLOBALS);
    }

    /**
     * Get the search repository registered as global repositories for the user specified in input.
     * @param profile user profile
     * @return the global search repositories.
     */
    public static SearchRepository getGlobalSearchRepository(Profile profile) {
        for (SearchRepository sr: GLOBALS) {
            if (profile.equals(sr.getProfile())) {
                return sr;
            }
        }
        return null;
    }

    /**
     * clear global repositories
     */
    static void clearGlobalRepositories() {
        GLOBALS.clear();
    }

    /**
     * Add a global repository
     */
    public void addGlobalRepository() {
        GLOBALS.add(this);
    }

    /**
     * Returns an unmodifiable view of the the search items observed by this
     * repository.
     * @return An unmodifiable Set.
     */
    public Set<WebSearchable> getSearchItems() {
        return Collections.unmodifiableSet(new HashSet<WebSearchable>(searchItems));
    }

    /**
     * Get a map from name -&gt; object for all the websearchables of the given type.
     * @param type A valid TagType.
     * @return A new unmodifiable map containing the requested information.
     */
    public Map<String, WebSearchable> getWebSearchableMap(String type) {
        if (type == null) {
            throw new IllegalArgumentException("'type' may not be null");
        }
        Map<String, WebSearchable> retval = new HashMap<String, WebSearchable>();
        for (WebSearchable webSearchable : searchItems) {
            if (!type.equals(webSearchable.getTagType())) {
                continue;
            }
            retval.put(webSearchable.getName(), webSearchable);
        }
        return Collections.unmodifiableMap(retval);
    }

    /**
     * Collect the search items we are interested in initially. The profile associated with this
     * repository is a good place to start looking, but each implementation may decide it is
     * interested in a different subset of the available objects.
     */
    protected abstract void populateSearchItems();

    @Override
    public void receiveEvent(ChangeEvent e) {
        LOG.debug("Received " + e);
        if (e instanceof PropertyChangeEvent) {
            handlePropertyChange((PropertyChangeEvent) e);
        } else if (e instanceof DeletionEvent) {
            handleDeletion((DeletionEvent) e);
        } else if (e instanceof CreationEvent) {
            handleCreation((CreationEvent) e);
        } else if (e instanceof TaggingEvent) {
            TaggingEvent te = (TaggingEvent) e;
            switch (te.getAction()) {
                case ADDED:
                    handleTagAddition(te);
                    break;
                case REMOVED:
                    handleTagRemoval(te);
                    break;
                default:
                    throw new IllegalStateException(
                            "Someone added a TagChange and didn't update this section of the code");
            }
        } else if (e instanceof MassTaggingEvent) {
            handleMassTagging();
        }
        // Propagate all events to the global repository for this user.
        if (!GLOBALS.contains(this)) {
            // No infinite recursion please...
            for (SearchRepository sr: GLOBALS) {
                if (profile.equals(sr.getProfile())) {
                    sr.receiveEvent(e);
                }
            }
        }
    }

    /**
     * Get the profile that this repository belongs to.
     * @return A profile.
     */
    protected Profile getProfile() {
        return profile;
    }

    /**
     * Drop the index that contains information about the websearchable object passed in.
     * @param ws The item whose cached information we wish to dispose of.
     */
    protected synchronized void dropIndex(WebSearchable ws) {
        indexVersion++;
        indexes.remove(ws.getTagType());
    }

    /** Invalidate every cached index after a bulk change to the searchable items. */
    protected synchronized void clearIndexes() {
        indexVersion++;
        indexes.clear();
    }

    /**
     * Retrieve a search index for a given type of web searchable objects. If there is already
     * an index around, that will be returned. If not, one will be created and returned.
     * @param type The type of the web searchables to index. Should be a valid TagType.
     * @return an in-memory directory containing the index.
     */
    public Directory getSearchIndex(String type) {
        return getIndex(type).directory;
    }

    /**
     * Return an index together with the object identities captured when it was built.
     * @param type the indexed object type
     * @return a consistent index snapshot
     */
    CachedIndex getIndex(String type) {
        while (true) {
            long version;
            Set<WebSearchable> items;
            synchronized (this) {
                CachedIndex cached = indexes.get(type);
                if (cached != null) {
                    return cached;
                }
                version = indexVersion;
                items = new HashSet<WebSearchable>(searchItems);
            }
            CachedIndex candidate = index(type, items, profile, indexId);
            synchronized (this) {
                if (version == indexVersion && !indexes.containsKey(type)) {
                    indexes.put(type, candidate);
                    return candidate;
                }
            }
            try {
                candidate.directory.close();
            } catch (IOException error) {
                throw new IllegalStateException("Failed to close unused search index", error);
            }
        }
    }

    /**
     * Index some WebSearchables and return the in-memory directory containing the index.
     *
     * @param type The type of the websearchables to index. Should be a valid TagType.
     * @param items The collection of websearchables watched by this repository.
     * @param profile The profile of the owner of the websearchables.
     * @param indexId The repository identifier stored with each document.
     * @return an in-memory directory containing the index
     */
    private static CachedIndex index(String type, Collection<WebSearchable> items, Profile profile,
                                     String indexId) {
        if (type == null) {
            throw new IllegalArgumentException("'type' may not be null");
        }
        long time = System.currentTimeMillis();
        LOG.info("Indexing webSearchable queries");

        TagManager tagManager = new TagManagerFactory(profile.getProfileManager()).getTagManager();
        Directory directory = new ByteBuffersDirectory();
        Map<Integer, WebSearchable> indexedItems = new HashMap<Integer, WebSearchable>();
        int indexed = 0;
        try (Analyzer analyzer = new WebSearchAnalyzer();
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            for (WebSearchable item : items) {
                if (type.equals(item.getTagType())) {
                    writer.addDocument(createDocument(item, profile, tagManager, indexId, indexed));
                    indexedItems.put(indexed, item);
                    indexed++;
                }
            }
        } catch (IOException | RuntimeException error) {
            try {
                directory.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw new IllegalStateException("Failed to create search index", error);
        }
        LOG.debug("Indexed " + indexed + " webSearchables in "
                + (System.currentTimeMillis() - time) + " milliseconds");
        return new CachedIndex(directory, indexedItems);
    }

    private static Document createDocument(WebSearchable item, Profile profile,
                                            TagManager tagManager, String indexId, int itemId) {
        Document document = new Document();
        document.add(new StoredField("repository", indexId));
        document.add(new StoredField("item", itemId));
        document.add(new TextField("name", item.getName(), Field.Store.YES));
        StringBuilder content = new StringBuilder(item.getTitle() + " : " + item.getDescription());
        List<Tag> tags = tagManager.getTags(null, item.getName(), item.getTagType(),
                profile.getUsername());
        StringBuilder tagText = new StringBuilder();
        for (Tag tag : tags) {
            String tagName = tag.getTagName();
            tagText.append(' ').append(tagName);
            content.append(' ').append(AspectTagUtil.isAspectTag(tagName)
                    ? AspectTagUtil.getAspect(tagName) : tagName);
        }
        document.add(new TextField("tags", tagText.toString(), Field.Store.YES));
        document.add(new TextField("content", content.toString().replaceAll("[^a-zA-Z0-9]", " "),
                Field.Store.NO));
        return document;
    }

    /**
     * Respond in some way to the change in the properties of a web searchable object.
     * @param e The notification of the change event.
     */
    protected abstract void handlePropertyChange(PropertyChangeEvent e);

    /**
     * Respond in some way to the creation of a web searchable object.
     * @param e The notification of the change event.
     */
    protected abstract void handleCreation(CreationEvent e);

    /**
     * Respond in some way to the deletion of a web searchable object.
     * @param e The notification of the change event.
     */
    protected abstract void handleDeletion(DeletionEvent e);

    /**
     * Respond in some way to the addition of a tag to a web searchable object.
     * @param e The notification of the change event.
     */
    protected abstract void handleTagAddition(TaggingEvent e);

    /**
     * Respond in some way to the removal of a tag from a web searchable object.
     * @param e The notification of the change event.
     */
    protected abstract void handleTagRemoval(TaggingEvent e);

    /**
     * Respond to the fact that multiple tags have been added and that we do not know what they are.
     */
    protected abstract void handleMassTagging();

}
