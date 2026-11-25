package org.intermine.webservice.server.query;

import java.util.Properties;
import javax.servlet.http.HttpServletRequest;

import org.intermine.api.InterMineAPI;
import org.intermine.web.context.InterMineContext;
import org.intermine.web.logic.ClassResourceOpener;
import org.intermine.web.logic.config.WebConfig;
import org.junit.Before;

import org.junit.Test;

import static org.easymock.EasyMock.*;
import static org.junit.Assert.*;

public class QueryRequestParserTest
{
    @Before
    public void setup() {
        InterMineContext.initialise(createMock(InterMineAPI.class), new Properties(),
                new WebConfig(), new ClassResourceOpener(getClass()));
    }

    @Test
    public void decodedQueryParametersKeepTheirExactUnicodeText() {
        for (String name : new String[] {"A / β &amp;", "東京", "🧬", "Ã©", "é"}) {
            String xml = "<query view=\"Employee.name\"><constraint path=\"Employee\""
                    + " op=\"IN\" value=\"" + name + "\"/></query>";
            HttpServletRequest request = createNiceMock(HttpServletRequest.class);
            expect(request.getParameter("query")).andStubReturn(xml);
            expect(request.getCharacterEncoding()).andStubReturn("UTF-8");
            replay(request);
            assertEquals(xml, new QueryRequestParser(null, request).getQueryXml());
        }
    }

    @Test
    public void compressedDictionaryCodesSurviveTransportDecoding() {
        String encoded = "TOBEORNOT" + (char) 256 + (char) 258 + (char) 260
                + (char) 265 + (char) 259 + (char) 261 + (char) 263;
        assertEquals("TOBEORNOTTOBEORTOBEORNOT", QueryRequestParser.decodeLZWString(encoded));
    }
}
