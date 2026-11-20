package org.intermine.web.logic.profile;

/*
 * Copyright (C) 2002-2022 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.sql.Connection;
import java.sql.SQLException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.log4j.Logger;
import org.apache.struts.action.ActionMessage;
import org.intermine.api.InterMineAPI;
import org.intermine.api.bag.UnknownBagTypeException;
import org.intermine.api.profile.BagState;
import org.intermine.api.profile.Profile;
import org.intermine.api.profile.ProfileManager;
import org.intermine.api.profile.ProfileManager.ApiPermission;
import org.intermine.api.profile.SavedQuery;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.intermine.ObjectStoreWriterInterMineImpl;
import org.intermine.sql.DatabaseUtil;
import org.intermine.web.logic.session.SessionMethods;
import org.intermine.web.struts.InterMineAction;

/**
 * @author Xavier Watkins
 *
 *
 * Abstract class containing the methods for login in and copying current
 * history, bags,... into profile.
 *
 */
public abstract class LoginHandler extends InterMineAction
{
    private static final Logger LOG = Logger.getLogger(LoginHandler.class);

    /**
     * Log-in a user.
     *
     * After this method completes, SessionMethods::getProfile will return this
     * user's profile.
     *
     * @param request The HttpServletRequest
     * @param username The username
     * @param password The password
     * @return the map containing the renamed bags the user created before they were logged in
     */
    public ProfileMergeIssues doLogin(HttpServletRequest request,
            String username, String password) {
        ProfileMergeIssues issues = doStaticLogin(request, username, password);
        HttpSession session = request.getSession();
        ProfileManager pm = SessionMethods.getInterMineAPI(session).getProfileManager();
        Profile profile = pm.getProfile(username);
        InterMineAPI im = SessionMethods.getInterMineAPI(session);
        if (im.getBagManager().isAnyBagNotCurrentOrUpgrading(profile)) {
            recordError(new ActionMessage("login.upgradeListStarted"), request);
        } else if (im.getBagManager().isAnyBagToUpgrade(profile)) {
            recordError(new ActionMessage("login.upgradeListManually"), request);
        }
        return issues;
    }

    /**
     * Merge two profiles together. This is mainly of use when a new user registers and we need
     * to save their current anonymous session into their new profile.
     * @param fromProfile The profile to take information from.
     * @param toProfile The profile to merge into.
     * @return A map of bags, from old name to new name.
     */
    public static ProfileMergeIssues mergeProfiles(Profile fromProfile, Profile toProfile) {
        if (fromProfile == null || fromProfile.isLoggedIn()) {
            return new ProfileMergeIssues();
        }
        try {
            ProfileMergeIssues issues = AnonymousProfileMerger.transferBags(fromProfile, toProfile);
            toProfile.getPreferences().putAll(fromProfile.getPreferences());
            for (SavedQuery query : fromProfile.getHistory().values()) {
                toProfile.saveHistory(query);
            }
            return issues;
        } catch (ObjectStoreException | UnknownBagTypeException error) {
            throw new IllegalStateException("Could not transfer anonymous lists", error);
        }
    }

    /**
     * Main log-in logic.
     * @param request The current request.
     * @param username The current user's login name.
     * @param password The current user's password.
     * @return A map of renamed-bags from old to new name.
     */
    public static ProfileMergeIssues doStaticLogin(HttpServletRequest request,
            String username, String password) {
        Profile profile = authenticatedProfile(request.getSession(), username, password);
        return completeLogin(request, profile);
    }

    /**
     * Complete an already authenticated browser login, preserving anonymous work.
     * @param request the browser request
     * @param profile the authenticated, persisted profile
     * @return the names changed while transferring anonymous lists
     */
    public static ProfileMergeIssues completeLogin(HttpServletRequest request, Profile profile) {
        if (profile == null || !profile.isLoggedIn() || profile.getUserId() == null) {
            throw new IllegalArgumentException("An authenticated profile is required");
        }
        HttpSession session = request.getSession();
        ProfileMergeIssues issues = mergeProfiles(SessionMethods.getProfile(session), profile);
        request.changeSessionId();
        session.removeAttribute(AdminAccess.TOKEN_ATTRIBUTE);
        if (!profile.isSuperuser()
                && "users".equals(SessionMethods.getWebState(session).getSubtab("subtabmymine"))) {
            SessionMethods.getWebState(session).addSubtab("subtabmymine", "lists");
        }
        setUpProfile(session, profile);
        return issues;
    }

    /**
     * Initialises a profile for the current user based on their user name and password.
     *
     * @param session HTTP session
     * @param username user name
     * @param password password
     * @return profile, fully ready to use.
     */
    public static Profile setUpProfile(HttpSession session, String username, String password) {
        return setUpProfile(session, authenticatedProfile(session, username, password));
    }

    private static Profile authenticatedProfile(HttpSession session, String username,
            String password) {
        InterMineAPI im = SessionMethods.getInterMineAPI(session);
        Profile profile = im.getProfileManager().getProfile(username, password, im.getClassKeys());
        if (profile == null) {
            throw new LoginException("Invalid username or password");
        }
        return profile;
    }

    /**
     * Does whatever needs to be done to a permissions object to get it ready
     * for a life cyle in a web service request. At the moment, this just means
     * determining if this is the super user, and running the bag upgrade
     * thread.
     *
     * @param api
     *            The InterMine API object.
     * @param permission
     *            The permission that needs setting up.
     */
    public static void setUpPermission(InterMineAPI api,
            ApiPermission permission) {
        final ProfileManager pm = api.getProfileManager();
        final Profile profile = permission.getProfile();
        final String userName = profile.getUsername();
        if (profile.isSuperuser() || (userName != null && userName.equals(pm.getSuperuser()))) {
            permission.addRole("SUPERUSER");
        }
        if (!api.getBagManager().isAnyBagInState(profile, BagState.UPGRADING)) {
            UpgradeBagList upgrade = new UpgradeBagList(profile, api.getBagQueryRunner());
            runBagUpgrade(upgrade, api, profile);
        }
    }

    /**
     * Kick off a bag upgrade for current user.
     * @param procedure The bag upgrade routine.
     * @param api The InterMine state object.
     * @param profile The current user's profile.
     */
    public static void runBagUpgrade(
            UpgradeBagList procedure,
            InterMineAPI api,
            Profile profile) {
        Connection con = null;
        try {
            con = ((ObjectStoreWriterInterMineImpl) api.getProfileManager()
                    .getProfileObjectStoreWriter()).getDatabase()
                    .getConnection();
            if (api.getBagManager().isAnyBagNotCurrent(profile)
                    && !DatabaseUtil.isBagValuesEmpty(con)) {
                Thread upgrade = new Thread(procedure);
                upgrade.setDaemon(true);
                upgrade.start();
            }
        } catch (SQLException sqle) {
            LOG.error("Problems retrieving the connection", sqle);
        } finally {
            try {
                if (con != null) {
                    con.close();
                }
            } catch (SQLException sqle) {
                LOG.warn("Could not close the list-upgrade database connection", sqle);
            }
        }
    }
    /**
     * Sets up a profile ready for a session in InterMine.
     *
     * @param session http session
     * @param profile the user's profile (possibly anonymous and temporary)
     * @return profile The profile all cleaned up and good to go.
     */
    public static Profile setUpProfile(HttpSession session, Profile profile) {
        SessionMethods.setProfile(session, profile);
        final InterMineAPI im = SessionMethods.getInterMineAPI(session);
        UpgradeBagList upgrade = new UpgradeBagList(profile, im.getBagQueryRunner());
        runBagUpgrade(upgrade, im, profile);
        return profile;
    }

    private static final class LoginException extends RuntimeException
    {

        /**
         * Default serial id.
         */
        private static final long serialVersionUID = 1L;

        private LoginException() {
            super();
        }

        private LoginException(String message) {
            super(message);
        }
    }
}
