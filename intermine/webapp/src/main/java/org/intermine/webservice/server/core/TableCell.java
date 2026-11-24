package org.intermine.webservice.server.core;

/*
 * Copyright (C) 2002-2022 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.util.Set;

import org.intermine.api.results.ResultCell;
import org.intermine.metadata.TypeUtil;
import org.intermine.metadata.Util;
import org.intermine.model.FastPathObject;
import org.intermine.model.InterMineObject;
import org.intermine.pathquery.Path;

/**
 * A representation of a table cell.
 * @author Alex Kalderimis
 *
 */
public final class TableCell implements ResultCell
{
    private final FastPathObject fpo;
    private final boolean isKeyField;
    private final Path column;

    /**
     * Construct a table cell.
     * @param fpo The object backing this cell.
     * @param view The path this cell is a projection of.
     * @param isKeyField Whether this cell is a key field.
     */
    public TableCell(FastPathObject fpo, Path view, boolean isKeyField) {
        this.fpo = fpo;
        this.column = view;
        this.isKeyField = isKeyField;
    }

    /**
     * Construct a table cell for a null value.
     * @param view The path this cell is a projection of.
     */
    public TableCell(Path view) {
        this.fpo = null;
        this.column = view;
        this.isKeyField = false;
    }

    @Override
    public boolean isKeyField() {
        return isKeyField;
    }

    @Override
    public Object getField() {
        if (fpo == null) {
            return null;
        }
        try {
            return fpo.getFieldValue(column.getEndFieldDescriptor().getName());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Error retrieving field value for " + column, e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Error retrieving field value for " + column, e);
        }
    }

    @Override
    public FastPathObject getObject() {
        return fpo;
    }

    @Override
    public String getType() {
        if (fpo == null) {
            return null;
        }
        return TypeUtil.unqualifiedName(typeFor(fpo.getClass(), column).getName());
    }

    /**
     * The model class an object is reported as in a column. An object can have
     * several classes at once (a Gene that is both ProteinCodingGene and
     * NotInSystematicSequenceOfS288C). The class that fits the column's type
     * wins; otherwise the first class in name order.
     * @param clazz The class of the object, possibly a dynamic class.
     * @param view The column, or null.
     * @return One of the classes the object is made of.
     */
    static Class<?> typeFor(Class<?> clazz, Path view) {
        Set<Class<?>> classes = Util.decomposeClass(clazz);
        if (view != null && view.getLastClassDescriptor() != null) {
            Class<?> columnType = view.getLastClassDescriptor().getType();
            for (Class<?> c : classes) {
                if (columnType.isAssignableFrom(c)) {
                    return c;
                }
            }
        }
        return classes.iterator().next();
    }

    @Override
    public Path getPath() {
        return column;
    }

    @Override
    public Integer getId() {
        if (fpo instanceof InterMineObject) {
            return ((InterMineObject) fpo).getId();
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format("TableCell [ %s=%s ]", column, getField());
    }
}
