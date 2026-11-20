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
import java.util.HashMap;
import java.util.Map;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.SolrResponseBase;

/**
 * Checked writes shared by keyword and autocomplete indexing.
 * @author InterMine developers
 */
public final class SolrIndexSupport
{
    private SolrIndexSupport() { }

    /**
     * Add a multivalued, optional schema field.
     * @param client destination core
     * @param name field name
     * @param type field type
     * @param stored whether values are stored
     * @param indexed whether values are indexed
     * @throws IOException if communication fails
     * @throws SolrServerException if Solr rejects the field
     */
    public static void addField(SolrClient client, String name, String type,
                                boolean stored, boolean indexed)
        throws IOException, SolrServerException {
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put("name", name);
        attributes.put("type", type);
        attributes.put("stored", stored);
        attributes.put("indexed", indexed);
        attributes.put("multiValued", true);
        attributes.put("required", false);
        check(new SchemaRequest.AddField(attributes).process(client), "add field " + name);
    }

    /**
     * Reject error responses even when the HTTP request itself succeeded.
     * @param response Solr update response
     * @param operation operation being checked
     * @throws SolrServerException if the response reports an error
     */
    public static void check(SolrResponseBase response, String operation)
        throws SolrServerException {
        Object errors = response.getResponse().get("errors");
        if (response.getStatus() != 0 || errors != null) {
            throw new SolrServerException("Solr failed to " + operation + ": status "
                    + response.getStatus() + ", errors " + errors);
        }
    }
}
