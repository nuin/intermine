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

import java.net.URI;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;

/**
 * Creates clients from the configured URL of a Solr core.
 * @author InterMine developers
 */
public final class SolrClientFactory
{
    private SolrClientFactory() { }

    /**
     * Create a client for one configured core.
     * @param coreUrl absolute HTTP or HTTPS URL ending in the core name
     * @return a client owned by the caller
     */
    public static SolrClient create(String coreUrl) {
        if (coreUrl == null) {
            throw new IllegalArgumentException("Solr core URL must be configured");
        }
        URI uri = URI.create(coreUrl);
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.normalize().equals(uri)) {
            throw new IllegalArgumentException("Expected an absolute HTTP(S) Solr core URL");
        }
        String path = uri.getRawPath().replaceAll("/+$", "");
        int separator = path.lastIndexOf('/');
        String core = path.substring(separator + 1);
        if (separator <= 0 || !core.matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]*")
                || path.contains("//")) {
            throw new IllegalArgumentException("Solr URL must end in a core name after its root");
        }
        String root = uri.getScheme() + "://" + uri.getRawAuthority()
                + path.substring(0, separator);
        return new HttpJdkSolrClient.Builder(root).withDefaultCollection(core).build();
    }
}
