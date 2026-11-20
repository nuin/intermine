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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * English analysis shared by saved-item indexing and querying.
 * @author InterMine developers
 */
final class WebSearchAnalyzer extends Analyzer
{
    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        StandardTokenizer tokenizer = new StandardTokenizer();
        TokenStream tokens = new LowerCaseFilter(tokenizer);
        tokens = new StopFilter(tokens, EnglishAnalyzer.getDefaultStopSet());
        tokens = new SnowballFilter(tokens, "English");
        return new TokenStreamComponents(tokenizer, tokens);
    }

    @Override
    protected TokenStream normalize(String fieldName, TokenStream tokens) {
        return new LowerCaseFilter(tokens);
    }
}
