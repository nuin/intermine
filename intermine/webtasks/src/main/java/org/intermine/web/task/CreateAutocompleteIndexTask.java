package org.intermine.web.task;

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

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.intermine.objectstore.ObjectStore;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.ObjectStoreFactory;
import org.intermine.web.autocompletion.AutoCompleter;

/**
 * Rebuilds the autocomplete core from the configured object store.
 * @author InterMine developers
 */
public class CreateAutocompleteIndexTask extends Task
{
    private String osAlias;

    /**
     * Set the source object store alias.
     * @param alias object store alias
     */
    public void setOsAlias(String alias) {
        osAlias = alias;
    }

    @Override
    public void execute() {
        if (osAlias == null) {
            throw new BuildException("osAlias attribute is required");
        }
        ObjectStore store;
        try {
            store = ObjectStoreFactory.getObjectStore(osAlias);
        } catch (Exception error) {
            throw new BuildException("Could not open object store " + osAlias, error);
        }
        try {
            new AutoCompleter(store).buildIndex(store);
        } catch (IOException | ObjectStoreException | ClassNotFoundException
                 | SolrServerException error) {
            throw new BuildException("Creating autocomplete index failed", error);
        }
    }
}
