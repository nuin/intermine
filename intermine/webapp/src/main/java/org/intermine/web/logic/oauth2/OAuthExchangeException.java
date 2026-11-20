package org.intermine.web.logic.oauth2;

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
 * Sanitized exchange failure. Raw causes must not cross public logging boundaries.
 * @author InterMine Team
 */
public final class OAuthExchangeException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    private final int httpStatus;

    /**
     * @param message sanitized phase/status description without provider data
     * @param httpStatus public HTTP error status
     * @param cause internal cause, never log or attach it to a service error
     */
    public OAuthExchangeException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /** @return the public HTTP error status */
    public int getHttpStatus() {
        return httpStatus;
    }
}
