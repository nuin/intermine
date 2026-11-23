package org.intermine.webservice.server;

/*
 * Copyright (C) 2002-2022 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

/**
 * Enumeration for outer join status.
 *
 * @author Alex Kalderimis
 */
public enum ColumnHeaderStyle
{
    /** Use query paths as column headers. */
    PATH,
    /** Use human-readable column labels. */
    FRIENDLY,
    /** Omit column headers. */
    NONE
}
