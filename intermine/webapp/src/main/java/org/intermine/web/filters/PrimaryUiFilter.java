package org.intermine.web.filters;

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
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.function.Supplier;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.intermine.metadata.Util;
import org.intermine.model.InterMineObject;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.web.context.InterMineContext;
import org.intermine.web.logic.Constants;

/**
 * Opt-in primary UI bridge; APIs and legacy state-changing operations pass through.
 * @author InterMine developers
 */
public final class PrimaryUiFilter implements Filter
{
    private final Supplier<Properties> properties;
    private final PrimaryUiRoutes.ReportTypeResolver resolver;

    /** Construct the servlet-managed filter. */
    public PrimaryUiFilter() {
        this(InterMineContext::getWebProperties, PrimaryUiFilter::reportType);
    }

    /**
     * @param properties Current deployment properties
     * @param resolver Report object lookup
     */
    public PrimaryUiFilter(Supplier<Properties> properties,
            PrimaryUiRoutes.ReportTypeResolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    private static String reportType(int id) throws ObjectStoreException {
        InterMineObject object = InterMineContext.getInterMineAPI()
                .getObjectStore().getObjectById(id);
        return object == null ? null
                : Util.decomposeClass(object.getClass()).iterator().next().getSimpleName();
    }

    /** {@inheritDoc} */
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.isEmpty()) {
            path = "/";
        }
        if (!("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                || !PrimaryUiRoutes.isEntryPath(path)) {
            chain.doFilter(req, res);
            return;
        }
        PrimaryUiRoutes routes;
        try {
            routes = new PrimaryUiRoutes(properties.get().getProperty("bluegenes.primary.url"));
        } catch (IllegalArgumentException e) {
            throw new ServletException("Invalid bluegenes.primary.url configuration");
        }
        String target;
        try {
            HttpSession session = request.getSession(false);
            boolean draft = session != null && session.getAttribute(Constants.QUERY) != null;
            target = routes.destination(path, request.getParameterMap(), draft, resolver);
        } catch (IllegalArgumentException e) {
            uncached(response);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid BlueGenes entry link");
            return;
        } catch (NoSuchElementException e) {
            uncached(response);
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "The requested report does not exist");
            return;
        } catch (ObjectStoreException e) {
            throw new ServletException("Could not resolve report object", e);
        }
        if (target == null) {
            chain.doFilter(req, res);
        } else {
            uncached(response);
            String source = request.getRequestURL().toString().replaceFirst("/$", "");
            if (target.equals(source)) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "The primary UI URL points to this entry point");
            } else {
                response.setStatus(HttpServletResponse.SC_FOUND);
                response.setHeader("Location", target);
            }
        }
    }

    private static void uncached(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    /** {@inheritDoc} */
    @Override
    public void init(FilterConfig config) {
        // Web properties become available when the Struts initialiser runs.
    }

    /** {@inheritDoc} */
    @Override
    public void destroy() {
        // The application owns the properties and object store.
    }
}
