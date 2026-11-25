package org.intermine.webservice.server.query;

/*
 * Copyright (C) 2002-2022 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang.StringUtils;

import org.intermine.api.query.QueryStore;
import org.intermine.api.query.QueryStoreException;
import org.intermine.webservice.server.WebServiceRequestParser;
import org.intermine.webservice.server.exceptions.BadRequestException;

/**
 * Reads query XML from servlet-decoded request parameters, compressed queries,
 * or the query store.
 *
 * @author Alex Kalderimis
 **/
public class QueryRequestParser extends WebServiceRequestParser
{

    protected HttpServletRequest request;

    private QueryStore queryStore;

    /**
     * RequestProcessor constructor.
     * @param request request
     * @param queryStore a place to retrieve queries by qid.
     */
    public QueryRequestParser(QueryStore queryStore, HttpServletRequest request) {
        this.queryStore = queryStore;
        this.request = request;
    }

    private static final String QUERY_PARAMETER = "query";

    /**
     * The query parameter for queries passed as a
     * lzw compressed string.
     */
    private static final String QLZW_PARAMETER = "qlzw";

    private static final String QID = "qid";

    /**
     * Decompress a list of output ks to a string.
     *
     * Gratefully nicked from Stack-Overflow.
     * @param compressed A query compressed to a list of bytes.
     * @return The decompressed query.
     **/
    public static String decompressLZW(List<Integer> compressed) {
        // Build the dictionary.
        int dictSize = 256;
        Map<Integer, String> dictionary = new HashMap<Integer, String>();
        for (int i = 0; i < 256; i++) {
            dictionary.put(i, "" + (char) i);
        }

        String w = "" + (char) (int) compressed.remove(0);
        String result = w;
        for (int k : compressed) {
            String entry;
            if (dictionary.containsKey(k)) {
                entry = dictionary.get(k);
            } else if (k == dictSize) {
                entry = w + w.charAt(0);
            } else {
                throw new IllegalArgumentException("Bad compressed k: " + k);
            }

            result += entry;

            // Add w+entry[0] to the dictionary.
            dictionary.put(dictSize++, w + entry.charAt(0));

            w = entry;
        }
        return result;
    }

    /**
     * Take in a LZW encoded string and return a decoded plain-text string.
     * @return the decompressed query.
     * @param encoded The compressed and encoded representation of the query.
     */
    public static String decodeLZWString(String encoded) {
        List<Integer> codes = new ArrayList<Integer>();
        int length = encoded.length();
        for (int i = 0; i < length; i++) {
            Integer cp = Integer.valueOf(encoded.codePointAt(i));
            codes.add(cp);
        }
        return decompressLZW(codes);
    }

    /**
     * Get query XML from a request.
     * @return The XML string version of the query, in the correct encoding.
     */
    public String getQueryXml() {
        String xmlQuery, lzwQuery, qid;
        qid = request.getParameter(QID);
        xmlQuery = request.getParameter(QUERY_PARAMETER);
        lzwQuery = request.getParameter(QLZW_PARAMETER);

        if (StringUtils.isNotBlank(qid)) {
            try {
                return queryStore.getQuery(qid);
            } catch (QueryStoreException e) {
                throw new BadRequestException(e.getMessage());
            }
        } else if (StringUtils.isNotBlank(lzwQuery)) {
            xmlQuery = decodeLZWString(lzwQuery);
        }
        if (StringUtils.isBlank(xmlQuery)) {
            throw new BadRequestException("The 'query' parameter must not be blank");
        }
        return xmlQuery;
    }
}
