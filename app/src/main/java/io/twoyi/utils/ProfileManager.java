/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Manages profile configurations for twoyi container.
 * Handles CRUD operations and profile persistence.
 */
public class ProfileManager {

    private static final String TAG = "ProfileManager";
    private static final String PROFILES_KEY = "profiles_data";
    private static final String ACTIVE_PROFILE_KEY = "active_profile_id";
    private static final String DEFAULT_PROFILE_ID = "default";

    private static ProfileManager instance;
    private final Context context;
    private List<Profile> profiles;
    private String activeProfileId;

    private ProfileManager(Context context) {
        this.context = context.getApplicationContext();
        loadProfiles();
    }

    /**
     * Get the singleton instance
     */
    public static synchronized ProfileManager getInstance(Context context) {
        if (instance == null) {
            instance = new ProfileManager(context);
        }
        return instance;
    }

    /**
     * Load profiles from persistent storage
     */
    private void loadProfiles() {
        profiles = new ArrayList<>();
        String profilesJson = AppKV.getStringConfig(context, PROFILES_KEY, "");
        activeProfileId = AppKV.getStringConfig(context, ACTIVE_PROFILE_KEY, DEFAULT_PROFILE_ID);

        if (profilesJson.isEmpty()) {
            // Create default profile
            Profile defaultProfile = createDefaultProfile();
            profiles.add(defaultProfile);
            saveProfiles();
        } else {
            try {
                JSONArray jsonArray = new JSONArray(profilesJson);
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    Profile profile = Profile.fromJson(jsonObject);
                    profiles.add(profile);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse profiles", e);
                // Reset to default if corrupted
                profiles.clear();
                Profile defaultProfile = createDefaultProfile();
                profiles.add(defaultProfile);
                saveProfiles();
            }
        }

        // Ensure active profile exists
        if (getProfileById(activeProfileId) == null && !profiles.isEmpty()) {
            activeProfileId = profiles.get(0).getId();
            saveActiveProfileId();
        }
    }

    /**
     * Create the default profile
     */
    private Profile createDefaultProfile() {
        Profile profile = new Profile("Default");
        profile.setId(DEFAULT_PROFILE_ID);
        return profile;
    }

    /**
     * Save profiles to persistent storage
     */
    private void saveProfiles() {
        try {
            JSONArray jsonArray = new JSONArray();
            for (Profile profile : profiles) {
                jsonArray.put(profile.toJson());
            }
            AppKV.setStringConfig(context, PROFILES_KEY, jsonArray.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save profiles", e);
        }
    }

    /**
     * Save active profile ID
     */
    private void saveActiveProfileId() {
        AppKV.setStringConfig(context, ACTIVE_PROFILE_KEY, activeProfileId);
    }

    /**
     * Get all profiles
     */
    public List<Profile> getAllProfiles() {
        return new ArrayList<>(profiles);
    }

    /**
     * Get profiles sorted by last used time (most recent first)
     */
    public List<Profile> getProfilesSortedByLastUsed() {
        List<Profile> sorted = new ArrayList<>(profiles);
        Collections.sort(sorted, (p1, p2) -> Long.compare(p2.getLastUsedAt(), p1.getLastUsedAt()));
        return sorted;
    }

    /**
     * Get a profile by its ID
     */
    public Profile getProfileById(String id) {
        for (Profile profile : profiles) {
            if (profile.getId().equals(id)) {
                return profile;
            }
        }
        return null;
    }

    /**
     * Get the active profile
     */
    public Profile getActiveProfile() {
        Profile profile = getProfileById(activeProfileId);
        if (profile == null && !profiles.isEmpty()) {
            profile = profiles.get(0);
            activeProfileId = profile.getId();
            saveActiveProfileId();
        }
        return profile;
    }

    /**
     * Set the active profile
     */
    public void setActiveProfile(String profileId) {
        if (getProfileById(profileId) != null) {
            activeProfileId = profileId;
            saveActiveProfileId();
        }
    }

    /**
     * Add a new profile
     */
    public void addProfile(Profile profile) {
        profiles.add(profile);
        saveProfiles();
    }

    /**
     * Update an existing profile
     */
    public void updateProfile(Profile profile) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).getId().equals(profile.getId())) {
                profiles.set(i, profile);
                saveProfiles();
                return;
            }
        }
    }

    /**
     * Delete a profile by ID
     * Returns false if trying to delete the last profile
     */
    public boolean deleteProfile(String profileId) {
        if (profiles.size() <= 1) {
            return false; // Cannot delete the last profile
        }

        Profile toRemove = null;
        for (Profile profile : profiles) {
            if (profile.getId().equals(profileId)) {
                toRemove = profile;
                break;
            }
        }

        if (toRemove != null) {
            profiles.remove(toRemove);
            
            // If deleted profile was active, switch to first available
            if (activeProfileId.equals(profileId)) {
                activeProfileId = profiles.get(0).getId();
                saveActiveProfileId();
            }
            
            saveProfiles();
            return true;
        }
        return false;
    }

    /**
     * Create a duplicate of an existing profile
     */
    public Profile duplicateProfile(String profileId) {
        Profile original = getProfileById(profileId);
        if (original == null) {
            return null;
        }

        try {
            Profile duplicate = Profile.fromJson(original.toJson());
            duplicate.setId(java.util.UUID.randomUUID().toString());
            duplicate.setName(original.getName() + " (Copy)");
            duplicate.setCreatedAt(System.currentTimeMillis());
            duplicate.setLastUsedAt(System.currentTimeMillis());
            addProfile(duplicate);
            return duplicate;
        } catch (JSONException e) {
            Log.e(TAG, "Failed to duplicate profile", e);
            return null;
        }
    }

    /**
     * Get the rootfs directory for a profile.
     * If the profile has a custom rootfs path that is a valid file path, use that.
     * Otherwise, use a profile-specific subdirectory.
     * Note: Content URIs (content://) are not supported as rootfs paths.
     */
    public File getRootfsDir(Profile profile) {
        String rootfsPath = profile.getRootfsPath();
        if (rootfsPath != null && !rootfsPath.isEmpty()) {
            // Check if it's a valid file path (not a content URI)
            if (!rootfsPath.startsWith("content://")) {
                File customPath = new File(rootfsPath);
                // Verify the path looks valid
                if (customPath.isAbsolute()) {
                    return customPath;
                }
            }
            // Content URIs or invalid paths fall through to default handling
        }
        
        // Use profile-specific subdirectory
        if (DEFAULT_PROFILE_ID.equals(profile.getId())) {
            // Default profile uses the standard rootfs directory
            return new File(context.getDataDir(), "rootfs");
        } else {
            // Other profiles use a subdirectory named by profile ID
            return new File(context.getDataDir(), "rootfs_" + sanitizeForPath(profile.getId()));
        }
    }

    /**
     * Sanitize a string for use in a file path
     */
    private String sanitizeForPath(String input) {
        // Keep only alphanumeric characters and limit length
        String sanitized = input.replaceAll("[^a-zA-Z0-9-]", "");
        sanitized = sanitized.substring(0, Math.min(sanitized.length(), 32));
        if (sanitized.isEmpty()) {
            // Fallback value to avoid empty directory names
            return "default";
        }
        return sanitized;
    }

    /**
     * Check if a profile has an initialized rootfs
     */
    public boolean isProfileRootfsInitialized(Profile profile) {
        File rootfsDir = getRootfsDir(profile);
        File initFile = new File(rootfsDir, "init");
        return initFile.exists();
    }

    /**
     * Get profile count
     */
    public int getProfileCount() {
        return profiles.size();
    }

    /**
     * Check if a profile name is unique
     */
    public boolean isNameUnique(String name, String excludeProfileId) {
        for (Profile profile : profiles) {
            if (profile.getName().equalsIgnoreCase(name) && 
                !profile.getId().equals(excludeProfileId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Generate a unique profile name
     */
    public String generateUniqueName(String baseName) {
        String name = baseName;
        int counter = 1;
        while (!isNameUnique(name, null)) {
            name = baseName + " " + counter;
            counter++;
        }
        return name;
    }
}
