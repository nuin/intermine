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

import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.intermine.api.InterMineAPI;
import org.intermine.api.profile.DuplicateMappingException;
import org.intermine.api.profile.Profile;
import org.intermine.api.profile.UserPreferences;
import org.intermine.web.struts.oauth2.DelegatedIdentity;

/**
 * Resolves provider identities and updates their display preferences.
 * @author InterMine Team
 */
public final class OAuthIdentityProfiles
{
    private OAuthIdentityProfiles() {
    }

    /**
     * @param api the mine
     * @param identity verified userinfo identity
     * @return the profile selected by provider and ID, never by email
     */
    public static Profile getProfile(InterMineAPI api, DelegatedIdentity identity) {
        Profile profile = api.getProfileManager().grantPermission(
                identity.getProvider(), identity.getId(), api.getClassKeys()).getProfile();
        Map<String, String> preferences = profile.getPreferences();
        preferences.put(UserPreferences.EMAIL, identity.getEmail());
        String name = identity.getName();
        if (!StringUtils.isEmpty(name)) {
            if (StringUtils.isEmpty(preferences.get(UserPreferences.AKA))) {
                preferences.put(UserPreferences.AKA, name);
            }
            if (StringUtils.isEmpty(preferences.get(UserPreferences.ALIAS))) {
                setAvailableAlias(preferences, name);
            }
        }
        return profile;
    }

    private static void setAvailableAlias(Map<String, String> preferences, String name) {
        int suffix = 0;
        while (true) {
            String alias = suffix == 0 ? name : name + " " + suffix;
            try {
                preferences.put(UserPreferences.ALIAS, alias);
                return;
            } catch (DuplicateMappingException e) {
                suffix++;
            }
        }
    }
}
