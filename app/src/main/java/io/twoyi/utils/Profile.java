/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a profile configuration for twoyi container.
 * Each profile contains settings for rootfs path, ports, and other configurations.
 */
public class Profile {

    public static final String MODE_LEGACY = "legacy";
    public static final String MODE_SERVER = "server";

    private String id;
    private String name;
    private String rootfsPath;
    private String controlPort;
    private String adbPort;
    private String mode;
    private boolean verboseDebug;
    private boolean use3rdPartyRom;
    private long createdAt;
    private long lastUsedAt;

    /**
     * Create a new profile with default values
     */
    public Profile() {
        this.id = UUID.randomUUID().toString();
        this.name = "Default";
        this.rootfsPath = "";
        this.controlPort = AppKV.DEFAULT_SERVER_ADDRESS.split(":")[1];
        this.adbPort = AppKV.DEFAULT_ADB_ADDRESS.split(":")[1];
        this.mode = MODE_LEGACY;
        this.verboseDebug = false;
        this.use3rdPartyRom = false;
        this.createdAt = System.currentTimeMillis();
        this.lastUsedAt = System.currentTimeMillis();
    }

    /**
     * Create a profile with a specific name
     */
    public Profile(String name) {
        this();
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRootfsPath() {
        return rootfsPath;
    }

    public void setRootfsPath(String rootfsPath) {
        this.rootfsPath = rootfsPath;
    }

    public String getControlPort() {
        return controlPort;
    }

    public void setControlPort(String controlPort) {
        this.controlPort = controlPort;
    }

    public String getAdbPort() {
        return adbPort;
    }

    public void setAdbPort(String adbPort) {
        this.adbPort = adbPort;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isVerboseDebug() {
        return verboseDebug;
    }

    public void setVerboseDebug(boolean verboseDebug) {
        this.verboseDebug = verboseDebug;
    }

    public boolean isUse3rdPartyRom() {
        return use3rdPartyRom;
    }

    public void setUse3rdPartyRom(boolean use3rdPartyRom) {
        this.use3rdPartyRom = use3rdPartyRom;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(long lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public void updateLastUsed() {
        this.lastUsedAt = System.currentTimeMillis();
    }

    /**
     * Get the full server address (host:port)
     */
    public String getServerAddress() {
        return "127.0.0.1:" + controlPort;
    }

    /**
     * Get the full ADB address (host:port)
     */
    public String getAdbAddress() {
        return "127.0.0.1:" + adbPort;
    }

    /**
     * Check if this profile is configured for legacy mode
     */
    public boolean isLegacyMode() {
        return MODE_LEGACY.equals(mode);
    }

    /**
     * Check if this profile is configured for server mode
     */
    public boolean isServerMode() {
        return MODE_SERVER.equals(mode);
    }

    /**
     * Serialize profile to JSON
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("rootfsPath", rootfsPath);
        json.put("controlPort", controlPort);
        json.put("adbPort", adbPort);
        json.put("mode", mode);
        json.put("verboseDebug", verboseDebug);
        json.put("use3rdPartyRom", use3rdPartyRom);
        json.put("createdAt", createdAt);
        json.put("lastUsedAt", lastUsedAt);
        return json;
    }

    /**
     * Deserialize profile from JSON
     */
    public static Profile fromJson(JSONObject json) throws JSONException {
        Profile profile = new Profile();
        profile.id = json.getString("id");
        profile.name = json.getString("name");
        profile.rootfsPath = json.optString("rootfsPath", "");
        profile.controlPort = json.optString("controlPort", AppKV.DEFAULT_SERVER_ADDRESS.split(":")[1]);
        profile.adbPort = json.optString("adbPort", AppKV.DEFAULT_ADB_ADDRESS.split(":")[1]);
        profile.mode = json.optString("mode", MODE_LEGACY);
        profile.verboseDebug = json.optBoolean("verboseDebug", false);
        profile.use3rdPartyRom = json.optBoolean("use3rdPartyRom", false);
        profile.createdAt = json.optLong("createdAt", System.currentTimeMillis());
        profile.lastUsedAt = json.optLong("lastUsedAt", System.currentTimeMillis());
        return profile;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Profile profile = (Profile) o;
        return Objects.equals(id, profile.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Profile{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", mode='" + mode + '\'' +
                ", rootfsPath='" + rootfsPath + '\'' +
                '}';
    }
}
