package org.intermine.api.searchengine.solr;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.AnalyzerDefinition;
import org.apache.solr.client.solrj.request.schema.FieldTypeDefinition;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.schema.FieldTypeRepresentation;
import org.apache.solr.common.SolrInputDocument;
import org.intermine.api.searchengine.IndexHandler;
import org.intermine.api.searchengine.KeywordSearchFacetData;
import org.intermine.api.searchengine.KeywordSearchPropertiesManager;
import org.intermine.metadata.FieldDescriptor;
import org.intermine.objectstore.ObjectStore;

/**
 * Builds a keyword index and propagates schema, fetch, and write failures.
 * @author InterMine developers
 */
public final class SolrIndexHandler implements IndexHandler
{
    private static final Logger LOG = Logger.getLogger(SolrIndexHandler.class);
    private static final String ANALYZED_FIELD_TYPE_NAME = "analyzed_string";
    private static final String RAW_FIELD_TYPE_NAME = "raw_string";

    @Override
    public void createIndex(ObjectStore os, Map<String, List<FieldDescriptor>> classKeys)
        throws IOException, SolrServerException {
        createIndex(os, classKeys, SolrClientManager.getClientInstance(os));
    }

    /**
     * Rebuild the index using a caller-owned client.
     * @param os source object store
     * @param classKeys model class keys
     * @param client destination core client
     * @throws IOException if communication fails
     * @throws SolrServerException if Solr rejects an indexing operation
     */
    void createIndex(ObjectStore os, Map<String, List<FieldDescriptor>> classKeys,
                     SolrClient client)
        throws IOException, SolrServerException {
        long started = System.currentTimeMillis();
        SolrIndexSupport.check(client.deleteByQuery("*:*"), "delete the old keyword index");
        SolrIndexSupport.check(client.commit(), "commit the old index deletion");
        List<String> existingFields = getExistingFields(client);
        List<String> existingTypes = getExistingTypes(client);
        List<Map<String, Object>> existingCopies = new ArrayList<Map<String, Object>>(
                new SchemaRequest.CopyFields().process(client).getCopyFields());
        addFieldType(client, existingTypes, ANALYZED_FIELD_TYPE_NAME,
                "solr.WhitespaceTokenizerFactory");
        addFieldType(client, existingTypes, RAW_FIELD_TYPE_NAME, "solr.KeywordTokenizerFactory");
        KeywordSearchPropertiesManager properties = KeywordSearchPropertiesManager.getInstance(os);
        List<String> indexedFields = new ArrayList<String>(existingFields);
        addField(client, indexedFields, "classname", ANALYZED_FIELD_TYPE_NAME);
        addField(client, indexedFields, "Category", "string");
        for (KeywordSearchFacetData facet : properties.getFacets()) {
            for (String field : facet.getFields()) {
                addField(client, indexedFields, field, ANALYZED_FIELD_TYPE_NAME);
                addField(client, indexedFields, "facet_" + field, "string");
                addCopyField(client, existingCopies, field, "facet_" + field);
            }
        }
        BlockingQueue<SolrInputDocument> queue = new ArrayBlockingQueue<SolrInputDocument>(100000);
        SolrObjectHandler fetcher = new SolrObjectHandler(os, properties.getClassKeys(), queue,
                properties.getIgnoredClasses(), properties.getIgnoredFields(),
                properties.getSpecialReferences(), properties.getClassBoost(),
                properties.getFacets(),
                properties.getAttributePrefixes(), client, indexedFields, existingFields);
        fetcher.start();
        Throwable failure = null;
        int indexed;
        try {
            indexed = writeDocuments(client, queue, fetcher, properties.getIndexBatchSize());
            SolrIndexSupport.check(client.commit(), "commit the keyword index");
            if (properties.getEnableOptimize()) {
                SolrIndexSupport.check(client.optimize(), "optimize the keyword index");
            }
        } catch (IOException | SolrServerException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            stopFetcher(fetcher, failure);
        }
        LOG.info("Indexed " + indexed + " documents in "
                + (System.currentTimeMillis() - started) + " ms");
    }

    private int writeDocuments(SolrClient client, BlockingQueue<SolrInputDocument> queue,
                               SolrObjectHandler fetcher, int batchSize)
        throws IOException, SolrServerException {
        List<SolrInputDocument> batch = new ArrayList<SolrInputDocument>();
        int indexed = 0;
        try {
            while (!fetcher.isFinished() || !queue.isEmpty()) {
                SolrInputDocument document = queue.poll(100, TimeUnit.MILLISECONDS);
                if (document == null) {
                    continue;
                }
                batch.add(document);
                indexed++;
                if (batch.size() >= batchSize) {
                    SolrIndexSupport.check(client.add(batch), "write keyword documents");
                    batch.clear();
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Keyword indexing was interrupted", error);
        }
        if (fetcher.getException() != null) {
            throw new SolrServerException("Fetching keyword index documents failed",
                    fetcher.getException());
        }
        if (!batch.isEmpty()) {
            SolrIndexSupport.check(client.add(batch), "write keyword documents");
        }
        return indexed;
    }

    private void stopFetcher(SolrObjectHandler fetcher, Throwable failure) throws IOException {
        boolean interrupted = Thread.interrupted();
        IOException cleanupError = null;
        fetcher.interrupt();
        try {
            fetcher.join(30000);
            if (fetcher.isAlive()) {
                cleanupError = new IOException("Keyword document fetcher did not stop");
            }
        } catch (InterruptedException error) {
            interrupted = true;
            cleanupError = new IOException("Interrupted while stopping keyword document fetcher",
                    error);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (cleanupError != null) {
            if (failure != null) {
                failure.addSuppressed(cleanupError);
            } else {
                throw cleanupError;
            }
        }
    }

    private void addField(SolrClient client, List<String> fields, String name, String type)
        throws IOException, SolrServerException {
        if (!fields.contains(name)) {
            SolrIndexSupport.addField(client, name, type, false, true);
            fields.add(name);
        }
    }

    private void addCopyField(SolrClient client, List<Map<String, Object>> copies,
                              String source, String destination)
        throws IOException, SolrServerException {
        for (Map<String, Object> copy : copies) {
            if (source.equals(copy.get("source")) && destination.equals(copy.get("dest"))) {
                return;
            }
        }
        SolrIndexSupport.check(new SchemaRequest.AddCopyField(source,
                java.util.Collections.singletonList(destination)).process(client),
                "add copy field");
        Map<String, Object> copy = new HashMap<String, Object>();
        copy.put("source", source);
        copy.put("dest", destination);
        copies.add(copy);
    }

    private void addFieldType(SolrClient client, List<String> types, String name, String tokenizer)
        throws IOException, SolrServerException {
        if (types.contains(name)) {
            return;
        }
        FieldTypeDefinition definition = new FieldTypeDefinition();
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put("name", name);
        attributes.put("class", "solr.TextField");
        attributes.put("positionIncrementGap", 100);
        attributes.put("multiValued", true);
        definition.setAttributes(attributes);
        AnalyzerDefinition analyzer = new AnalyzerDefinition();
        Map<String, Object> tokenizerAttributes = new HashMap<String, Object>();
        tokenizerAttributes.put("class", tokenizer);
        analyzer.setTokenizer(tokenizerAttributes);
        Map<String, Object> filter = new HashMap<String, Object>();
        filter.put("class", "solr.LowerCaseFilterFactory");
        analyzer.setFilters(java.util.Collections.singletonList(filter));
        definition.setIndexAnalyzer(analyzer);
        definition.setQueryAnalyzer(analyzer);
        SolrIndexSupport.check(new SchemaRequest.AddFieldType(definition).process(client),
                "add field type " + name);
        types.add(name);
    }

    private List<String> getExistingFields(SolrClient client)
        throws IOException, SolrServerException {
        List<String> fields = new ArrayList<String>();
        for (Map<String, Object> field : new SchemaRequest.Fields().process(client).getFields()) {
            fields.add((String) field.get("name"));
        }
        return fields;
    }

    private List<String> getExistingTypes(SolrClient client)
        throws IOException, SolrServerException {
        List<String> types = new ArrayList<String>();
        for (FieldTypeRepresentation type : new SchemaRequest.FieldTypes()
                .process(client).getFieldTypes()) {
            types.add((String) type.getAttributes().get("name"));
        }
        return types;
    }
}
