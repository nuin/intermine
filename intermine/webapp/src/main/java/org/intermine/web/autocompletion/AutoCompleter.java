package org.intermine.web.autocompletion;

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.intermine.metadata.ClassDescriptor;
import org.intermine.api.searchengine.solr.SolrIndexSupport;
import org.intermine.objectstore.ObjectStore;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.query.Query;
import org.intermine.objectstore.query.QueryClass;
import org.intermine.objectstore.query.QueryField;
import org.intermine.objectstore.query.ResultsRow;

/**
 * Autocompleter class for initializing and using the autocompletion
 *
 * @author Dominik Grimm
 * @author Michael Menden
 * @author arunans23
 */
public class AutoCompleter
{
    private static final Logger LOG = Logger.getLogger(AutoCompleter.class);

    private PropertiesManager propertiesManager;

    private HashMap<String, String> classFieldMap = new HashMap<String, String>();
    private HashMap<String, List<String>> fieldIndexMap = new HashMap<String, List<String>>();

    ObjectStore os;

    private static final String CLASSNAME_FIELD = "className";

    /**
     * Autocompleter build index constructor.
     *
     * @param os Objectstore
     */
    public AutoCompleter(ObjectStore os) {

        this.os = os;

        if (propertiesManager == null) {
            this.propertiesManager = PropertiesManager.getInstance();
            this.classFieldMap = propertiesManager.getClassFieldMap();
            createFieldIndexMap();
        }

    }


    /**
     * Returns n search results
     * @param query is the string used for search
     * @param field is the field in which you like to search (e.g. name)
     * @param className is the class in which you like to search (e.g. SOTerm)
     * @param n number of the first n search results
     * @return string array with search results and an error flag at position 0
     */
    public String[] getFastList(String query, String field, String className,  int n) {

        if (StringUtils.isBlank(query) || query.trim().startsWith("*")) {
            return null;
        }
        String terms = String.join("* AND " + field + ":", query.trim().split("\\s+"));
        SolrQuery solrQuery = new SolrQuery(field + ":" + terms + "*");
        solrQuery.setRequestHandler("select");
        solrQuery.setRows(n);
        solrQuery.setFilterQueries(CLASSNAME_FIELD + ":" + className);
        SolrClient client = SolrClientHandler.getClientInstance(propertiesManager.getSolrUrl());
        try {
            SolrDocumentList documents = client.query(solrQuery).getResults();
            String[] values = new String[documents.size() + 1];
            values[0] = "true";
            for (int i = 0; i < documents.size(); i++) {
                Object value = documents.get(i).getFirstValue(field);
                if (value == null) {
                    throw new IllegalStateException("Autocomplete result has no field " + field);
                }
                values[i + 1] = value.toString();
            }
            return values;
        } catch (SolrServerException | IOException error) {
            LOG.error("Autocomplete query failed", error);
            return new String[] {"Autocomplete is temporarily unavailable."};
        }
    }

    /**
     * Build the index from the database blob
     * @param os Objectstore
     * @throws IOException IOException
     * @throws ObjectStoreException ObjectStoreException
     * @throws ClassNotFoundException ClassNotFoundException
     * @throws SolrServerException solr exception
     */
    public void buildIndex(ObjectStore os)
        throws IOException, ObjectStoreException, ClassNotFoundException, SolrServerException {
        buildIndex(os, SolrClientHandler.getClientInstance(propertiesManager.getSolrUrl()));
    }

    /**
     * Rebuild autocomplete using a caller-owned client.
     * @param os source object store
     * @param solrClient destination core client
     * @throws IOException if communicating with Solr fails
     * @throws ObjectStoreException if fetching source values fails
     * @throws ClassNotFoundException if a configured model class is missing
     * @throws SolrServerException if Solr rejects an indexing operation
     */
    void buildIndex(ObjectStore os, SolrClient solrClient)
        throws IOException, ObjectStoreException, ClassNotFoundException, SolrServerException {

        List<String> fields = new ArrayList<String>();
        fields.add(CLASSNAME_FIELD);
        List<SolrInputDocument> documents = readDocuments(os, fields);
        SolrIndexSupport.check(solrClient.deleteByQuery("*:*"),
                "delete the old autocomplete index");
        SolrIndexSupport.check(solrClient.commit(),
                "commit the old autocomplete index deletion");
        List<String> existingFields = getAllExistingFieldsFromSolr(solrClient);
        for (String field : fields) {
            if (!existingFields.contains(field)) {
                SolrIndexSupport.addField(solrClient, field, "text_general", true, true);
            }
        }
        if (!documents.isEmpty()) {
            SolrIndexSupport.check(solrClient.add(documents), "write autocomplete documents");
        }
        SolrIndexSupport.check(solrClient.commit(), "commit the autocomplete index");
    }

    private List<SolrInputDocument> readDocuments(ObjectStore store, List<String> fields)
        throws ObjectStoreException, ClassNotFoundException {
        List<SolrInputDocument> documents = new ArrayList<SolrInputDocument>();
        for (Map.Entry<String, String> entry : classFieldMap.entrySet()) {
            ClassDescriptor descriptor = store.getModel().getClassDescriptorByName(entry.getKey());
            if (descriptor == null) {
                throw new IllegalStateException("Unknown autocomplete model class "
                        + entry.getKey());
            }
            for (String field : entry.getValue().split(" ")) {
                LOG.info("Indexing " + descriptor.getUnqualifiedName() + "." + field);
                if (!fields.contains(field)) {
                    fields.add(field);
                }
                Query query = new Query();
                query.setDistinct(true);
                QueryClass queryClass = new QueryClass(Class.forName(descriptor.getName()));
                query.addToSelect(new QueryField(queryClass, field));
                query.addFrom(queryClass);
                for (Object row : store.execute(query)) {
                    Object value = ((ResultsRow<?>) row).get(0);
                    if (value != null) {
                        SolrInputDocument document = new SolrInputDocument();
                        document.addField(field, value.toString());
                        document.addField(CLASSNAME_FIELD, descriptor.getUnqualifiedName());
                        documents.add(document);
                    }
                }
            }
        }
        return documents;
    }

    /**
     * checks if an autocompletion exists
     * @param type The name of the class to search.
     * @param field The name of the field to search for.
     * @return whether an autocompletion exists
     */
    public boolean hasAutocompleter(String type, String field) {

        if (fieldIndexMap.containsKey(type) && fieldIndexMap.get(type).contains(field)) {
            return true;
        }

        return false;
    }

    private void createFieldIndexMap() {
        for (Map.Entry<String, String> entry: classFieldMap.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            String className = key;
            ClassDescriptor cld = os.getModel().getClassDescriptorByName(className);
            if (cld == null) {
                throw new RuntimeException("a class mentioned in ObjectStore summary properties "
                        + "file (" + className + ") is not in the model");
            }
            List<String> fieldNames = Arrays.asList(value.split(" "));

            fieldIndexMap.put(cld.getUnqualifiedName(), fieldNames);
        }
    }

    private List<String> getAllExistingFieldsFromSolr(SolrClient solrClient)
            throws IOException, SolrServerException {
        List<String> allFields = new ArrayList<String>();
        SchemaRequest.Fields listFields = new SchemaRequest.Fields();
        SchemaResponse.FieldsResponse fieldsResponse = listFields.process(solrClient);
        List<Map<String, Object>> solrFields = fieldsResponse.getFields();
        for (Map<String, Object> field : solrFields) {
            allFields.add((String) field.get("name"));
        }
        return allFields;
    }

}
