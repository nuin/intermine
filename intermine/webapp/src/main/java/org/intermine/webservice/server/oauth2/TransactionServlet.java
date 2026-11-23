package org.intermine.webservice.server.oauth2;

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
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.intermine.web.context.InterMineContext;

/**
 * Routes transaction operations to the application-owned OAuth lifecycle.
 * @author InterMine Team
 */
public class TransactionServlet extends HttpServlet
{
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
        new TransactionService(InterMineContext.getInterMineAPI(),
                InterMineContext.getOAuthTransactions()).service(request, response);
    }
}
