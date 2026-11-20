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
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.intermine.api.config.ClassKeyHelper;
import org.intermine.api.searchengine.IndexHandler;
import org.intermine.api.searchengine.solr.SolrIndexHandler;
import org.intermine.metadata.FieldDescriptor;
import org.intermine.objectstore.ObjectStore;
import org.intermine.objectstore.ObjectStoreFactory;
import org.intermine.objectstore.intermine.ObjectStoreInterMineImpl;

/**
 * Create a keyword search index for a mine.
 * @author Alex Kalderimis
 *
 */
public class CreateSearchIndexTask extends Task
{

    protected String osAlias = null;
    protected ObjectStore os;
    private ClassLoader classLoader;

    /**
     * Set the alias of the main object store.
     * @param osAlias the object store alias
     */
    public void setOSAlias(String osAlias) {
        this.osAlias = osAlias;
    }

    /**
     * Set the object store.
     * @param os The object store.
     */
    public void setObjectStore(ObjectStore os) {
        this.os = os;
    }

    private ObjectStore getObjectStore() {
        if (os != null) {
            return os;
        }
        if (osAlias == null) {
            throw new BuildException("objectStoreWriter attribute is not set");
        }
        log("Connecting to db: " + osAlias);
        try {
            os = ObjectStoreFactory.getObjectStore(osAlias);
        } catch (Exception error) {
            throw new BuildException("Could not open object store " + osAlias, error);
        }
        return os;
    }

    /**
     * Set the class loader
     * @param loader The class loader.
     */
    public void setClassLoader(ClassLoader loader) {
        this.classLoader = loader;
    }

    private ClassLoader getClassLoader() {
        if (classLoader != null) {
            return classLoader;
        }
        return this.getClass().getClassLoader();
    }

    @Override
    public void execute() {
        log("Creating index for keyword search...");

        ObjectStore objectStore = getObjectStore();
        if (!(objectStore instanceof ObjectStoreInterMineImpl)) {
            // Yes, yes, this is horrific...
            throw new RuntimeException("Got invalid ObjectStore - must be an "
                    + "instance of ObjectStoreInterMineImpl!");
        }

        //read class keys to figure out what are keyFields during indexing
        Properties classKeyProperties = new Properties();
        try (InputStream is = getClassLoader().getResourceAsStream("class_keys.properties")) {
            if (is == null) {
                throw new BuildException("Could not find the class keys");
            }
            classKeyProperties.load(is);
        } catch (IOException e) {
            throw new BuildException("Could not read the class keys", e);
        }
        Map<String, List<FieldDescriptor>> classKeys =
            ClassKeyHelper.readKeys(objectStore.getModel(), classKeyProperties);

        //index and save. Deleting previous index happens within itself
        try {
            IndexHandler indexHandler = new SolrIndexHandler();
            indexHandler.createIndex(objectStore, classKeys);

        } catch (IOException | SolrServerException e) {
            throw new BuildException("Creating keyword index failed", e);
        }
    }


}
