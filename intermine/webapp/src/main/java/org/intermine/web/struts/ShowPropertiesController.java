package org.intermine.web.struts;

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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.intermine.web.logic.profile.AdminAccess;

/**
 * Authorizes the runtime properties view and prepares its mutation token.
 * @author InterMine developers
 */
public class ShowPropertiesController extends InterMineAction
{
    @Override
    public ActionForward execute(ActionMapping mapping, ActionForm form,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!AdminAccess.requireAdmin(request, response)) {
            return null;
        }
        request.setAttribute("adminToken", AdminAccess.issueToken(request.getSession()));
        return mapping.findForward("success");
    }
}
