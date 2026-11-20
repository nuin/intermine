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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.intermine.api.bag.UnknownBagTypeException;
import org.intermine.api.profile.BagState;
import org.intermine.api.profile.InterMineBag;
import org.intermine.api.profile.Profile;
import org.intermine.api.util.NameUtil;
import org.intermine.objectstore.ObjectStoreException;

/**
 * Transfers anonymous lists to an authenticated profile without sharing mutable bags.
 * @author InterMine Team
 */
public final class AnonymousProfileMerger
{
    private AnonymousProfileMerger() {
    }

    /**
     * Copy anonymous lists, removing each source only after its copy has been saved.
     * @param from the anonymous source, or null
     * @param to a different, persisted named profile
     * @return the names changed to avoid overwriting destination lists
     * @throws ObjectStoreException if persistence fails
     * @throws UnknownBagTypeException if a list type is absent from the model
     */
    public static ProfileMergeIssues transferBags(Profile from, Profile to)
        throws ObjectStoreException, UnknownBagTypeException {
        ProfileMergeIssues issues = new ProfileMergeIssues();
        if (from == null || from.isLoggedIn()) {
            return issues;
        }
        if (to == null || from == to || StringUtils.isBlank(to.getUsername())
                || to.getUserId() == null) {
            throw new IllegalArgumentException("A different named profile must receive the lists");
        }
        synchronized (from) {
            Map<String, InterMineBag> bags = new LinkedHashMap<String, InterMineBag>(
                    from.getSavedBags());
            for (Map.Entry<String, InterMineBag> entry : bags.entrySet()) {
                String name;
                synchronized (to) {
                    name = NameUtil.validateName(to.getSavedBags().keySet(), entry.getKey());
                    InterMineBag copy = copyBag(entry.getValue(), name, to);
                    to.saveBag(name, copy);
                }
                from.deleteBag(entry.getKey());
                if (!entry.getKey().equals(name)) {
                    issues.addRenamedBag(entry.getKey(), name);
                }
            }
        }
        return issues;
    }

    private static InterMineBag copyBag(InterMineBag bag, String name, Profile destination)
        throws ObjectStoreException, UnknownBagTypeException {
        List<Integer> ids = bag.getContentsAsIds();
        InterMineBag copy = new InterMineBag(name, bag.getType(), bag.getDescription(),
                bag.getDateCreated(), BagState.valueOf(bag.getState()),
                destination.getProfileManager().getProductionObjectStore(),
                destination.getUserId(), bag.getUserProfileWriter(), bag.getKeyFieldNames());
        try {
            copy.addIdsToBag(ids, bag.getType());
        } catch (ObjectStoreException | RuntimeException error) {
            try {
                copy.delete();
            } catch (ObjectStoreException | RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        return copy;
    }
}
