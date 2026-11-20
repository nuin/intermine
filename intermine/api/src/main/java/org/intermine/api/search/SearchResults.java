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

import static java.util.Arrays.asList;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.split;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.Formatter;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.NullFragmenter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.TokenGroup;
import org.intermine.api.profile.TagManager;
import org.intermine.api.profile.TagManagerFactory;
import org.intermine.template.TemplateQuery;

/**
 * Representations of Lucene search results over the web-searchable data of the user-profile and
 * the mechanisms for generating a set of such results.
 *
 * The LuceneSearchResults data structure encapsulates the relevant data returned from a search
 * over the user-profile's data. It should be constructed with a call to runLuceneSearch.
 *
 * @author Alex Kalderimis
 * @author Thomas Riley
 * @author Kim Rutherford
 */
public final class SearchResults implements Iterable<SearchResult>
{
    private static final Logger LOG = Logger.getLogger(SearchResults.class);

    /** The iterator for this iterable **/
    private static final class SearchResultIt implements Iterator<SearchResult>
    {
        private final SearchResults parent;
        private final Iterator<WebSearchable> subiter;

        SearchResultIt(SearchResults parent) {
            this.parent = parent;
            subiter = parent.items.values().iterator();
        }

        @Override
        public boolean hasNext() {
            return subiter.hasNext();
        }


        @Override
        public SearchResult next() {
            WebSearchable n = subiter.next();
            return new SearchResult(n, parent.hits.get(n), parent.descs.get(n), parent.tags.get(n));
        }

        @Override
        public void remove() {
            throw new RuntimeException("Not implemented");
        }

    }

    ///// INSTANCE API /////

    private final Map<WebSearchable, Float> hits = new IdentityHashMap<WebSearchable, Float>();
    private final Map<String, WebSearchable> items = new HashMap<String, WebSearchable>();
    private final Map<WebSearchable, String> descs = new IdentityHashMap<WebSearchable, String>();
    private final Map<WebSearchable, Set<String>> tags =
            new IdentityHashMap<WebSearchable, Set<String>>();

    private SearchResults() { }

    // Constructor only available to the static methods below.
    private SearchResults(
            Map<WebSearchable, Float> hitMap,
            Map<String, WebSearchable> items,
            Map<WebSearchable, String> descriptions,
            Map<WebSearchable, Set<String>> itemsTags) {
        this.hits.putAll(hitMap);
        this.items.putAll(items);
        this.descs.putAll(descriptions);
        this.tags.putAll(itemsTags);
    }

    /**
     *
     * @return size
     */
    public int size() {
        return items.size();
    }

    @Override
    public Iterator<SearchResult> iterator() {
        return new SearchResultIt(this);
    }

    ///// STATIC SEARCH API //////

    /**
     * Filter out invalid templates from a map.
     * @param map The map of websearchables. MUST be modifiable.
     */
    public static void filterOutInvalidTemplates(Map<String, ? extends WebSearchable> map) {
        List<String> removeKeys = new ArrayList<String>();
        for (String key : map.keySet()) {
            if (isInvalidTemplate(map.get(key))) {
                removeKeys.add(key);
            }
        }
        for (String key : removeKeys) {
            map.remove(key);
        }
    }

    /**
     * Check if a websearchable is an invalid template.
     * @param webSearchable The item to check.
     * @return True if this a template and it is not valid.
     */
    public static boolean isInvalidTemplate(WebSearchable webSearchable) {
        if (webSearchable instanceof TemplateQuery) {
            TemplateQuery template = (TemplateQuery) webSearchable;
            return !template.isValid();
        }
        return false;
    }

    /**
     * The formatter for presenting descriptions of web searchable objects.
     *
     * The term will be returned in a <code>&lt;span&gt;</code> element, with the css class
     * <code>im-highlighted-search-term</code> applied to it.
     */
    private static final Formatter FORMATTER = new Formatter() {
        @Override
        public String highlightTerm(String term, TokenGroup group) {
            if (group.getTotalScore() > 0) {
                return "<span class=\"im-highlighted-search-term\">" + term + "</span>";
            }
            return term;
        }
    };

    /**
     * Perform transformations on the query string to make it behave intuitively (for some
     * definition of intuition...).
     * @param origQueryString The original form of the query string.
     * @return A munged and transformed string, that will do what people actually want in Lucene.
     */
    private static String prepareQueryString(String origQueryString) {
        // special case for word ending in "log" eg. "ortholog" - add "orthologue" to the search
        String queryString = origQueryString.replaceAll("(\\w+log\\b)", "$1ue $1");
        queryString = queryString.replaceAll("[^a-zA-Z0-9]", " ").trim();
        queryString = queryString.replaceAll("(\\w+)$", "$1 $1*");
        return queryString;
    }

    private static MultiReader prepareReader(List<SearchRepository> repositories, String type,
            Map<String, Map<Integer, WebSearchable>> itemsByRepository) throws IOException {
        List<IndexReader> readers = new ArrayList<IndexReader>();
        try {
            for (SearchRepository repository : repositories) {
                SearchRepository.CachedIndex index = repository.getIndex(type);
                readers.add(DirectoryReader.open(index.directory));
                itemsByRepository.put(repository.getIndexId(), index.items);
            }
            return new MultiReader(readers.toArray(new IndexReader[0]));
        } catch (IOException | RuntimeException error) {
            for (IndexReader reader : readers) {
                try {
                    reader.close();
                } catch (IOException closeError) {
                    error.addSuppressed(closeError);
                }
            }
            throw error;
        }
    }

    private static SearchResults doFilteredSearch(String origQueryString, SearchTarget target,
            SearchRepository profileRepo) throws ParseException, IOException {
        List<SearchRepository> repositories = new ArrayList<SearchRepository>();
        if (!target.isUserOnly()) {
            repositories.addAll(SearchRepository.getGlobalSearchRepositories());
        }
        if (!target.isGlobalOnly()) {
            repositories.add(profileRepo);
        }
        SearchResults results = new SearchResults();
        if (repositories.isEmpty()) {
            return results;
        }
        String queryString = prepareQueryString(origQueryString);
        Map<String, Map<Integer, WebSearchable>> itemsByRepository =
                new HashMap<String, Map<Integer, WebSearchable>>();
        try (Analyzer analyzer = new WebSearchAnalyzer();
                MultiReader reader = prepareReader(repositories, target.getType(),
                        itemsByRepository)) {
            QueryParser parser = new QueryParser("content", analyzer);
            IndexSearcher searcher = new IndexSearcher(reader);
            org.apache.lucene.search.Query query = searcher.rewrite(parser.parse(queryString));
            TopDocs topDocs = searcher.search(query, 1000);
            LOG.debug("Found " + topDocs.totalHits + " for " + target);
            Highlighter highlighter = new Highlighter(FORMATTER, new QueryScorer(query));
            highlighter.setTextFragmenter(new NullFragmenter());
            Set<String> userHits = new HashSet<String>();
            for (ScoreDoc hit : topDocs.scoreDocs) {
                Document document = searcher.storedFields().document(hit.doc);
                String repositoryId = document.get("repository");
                String name = document.get("name");
                boolean userHit = profileRepo.getIndexId().equals(repositoryId);
                if (results.items.containsKey(name) && (!userHit || userHits.contains(name))) {
                    continue;
                }
                int itemId = document.getField("item").numericValue().intValue();
                WebSearchable item = itemsByRepository.get(repositoryId).get(itemId);
                if (item == null) {
                    throw new IllegalStateException("Unknown search item: " + name);
                }
                if (userHit) {
                    userHits.add(name);
                }
                results.addHit(item, hit.score, document, analyzer, highlighter);
            }
        }
        return results;
    }

    private void addHit(WebSearchable item, float score, Document document, Analyzer analyzer,
                        Highlighter highlighter) throws IOException {
        WebSearchable previous = items.put(document.get("name"), item);
        if (previous != null) {
            hits.remove(previous);
            descs.remove(previous);
            tags.remove(previous);
        }
        hits.put(item, score);
        tags.put(item, new HashSet<String>(asList(split(document.get("tags")))));
        String description = item.getDescription();
        if (description == null) {
            description = "";
        }
        try (TokenStream tokens = analyzer.tokenStream("content", description)) {
            descs.put(item, highlighter.getBestFragment(tokens, description));
        } catch (InvalidTokenOffsetsException error) {
            LOG.warn("Could not highlight search description", error);
        }
    }

    /**
     * Search a Lucene index and return a search result. Where the query string is blank, all
     * web searchable items of the requested scope will be returned.
     *
     * @param queryString the query string from the user.
     * @param target a parameter object containing information about the scope of the search.
     * @param userSearchRepository The current user's search repository.
     *
     * @throws ParseException if the origQueryString has a syntax error for a Lucene string
     * @throws IOException if there is a problem creating the Lucene IndexSearcher
     *
     * @return A result set.
     */
    public static SearchResults runLuceneSearch(
            String queryString, SearchTarget target,
            SearchRepository userSearchRepository)
        throws ParseException, IOException {

        if (isBlank(queryString)) {
            return doUnfilteredSearch(target, userSearchRepository);
        } else {
            return doFilteredSearch(queryString, target, userSearchRepository);
        }
    }

    /**
     * Return all the available objects in the requested scope in a set of search results.
     * @param target a parameter object containing information about the scope of the search.
     * @param userRepo The current user's search repository.
     * @return A set of search results.
     */
    private static SearchResults doUnfilteredSearch(SearchTarget target,
        SearchRepository userRepo) {
        LOG.info("unfiltered search");
        Map<String, WebSearchable> wsMap = new HashMap<String, WebSearchable>();
        Map<WebSearchable, Float> hitMap = new IdentityHashMap<WebSearchable, Float>();
        Map<WebSearchable, String> descs = new IdentityHashMap<WebSearchable, String>();
        Map<WebSearchable, Set<String>> tags = new IdentityHashMap<WebSearchable, Set<String>>();

        List<WebSearchable> items = new ArrayList<WebSearchable>();
        if (!target.isUserOnly()) {
            for (SearchRepository sr : SearchRepository.getGlobalSearchRepositories()) {
                items.addAll(sr.getSearchItems());
            }
        }
        if (!target.isGlobalOnly()) {
            items.addAll(userRepo.getSearchItems());
        }

        TagManager tm = new TagManagerFactory(userRepo.getProfile().getProfileManager())
                                .getTagManager();

        for (WebSearchable ws: items) {
            if (target.getType().equals(ws.getTagType())) {
                wsMap.put(ws.getName(), ws);
                descs.put(ws, ws.getDescription());
            }
            tags.put(ws, tm.getObjectTagNames(ws, userRepo.getProfile()));
        }

        return new SearchResults(hitMap, wsMap, descs, tags);
    }

}
