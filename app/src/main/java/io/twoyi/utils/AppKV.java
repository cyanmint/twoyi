/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * @author weishu
 * @date 2019/2/28.
 */
public class AppKV {


    private static final String PREF_NAME = "app_kv";

    public static final String ADD_APP_NOT_SHOW_SYSTEM= "add_app_not_show_system";
    public static final String ADD_APP_NOT_SHOW_ADDED = "add_app_not_show_added";
    public static final String SHOW_ANDROID12_TIPS = "show_android12_tips_v2";
    public static final String ADD_APP_NOT_SHOW_32BIT = "add_app_not_show_32bit";

    // 是否应该重新安装 ROM
    // 1. 恢复出厂设置
    // 2. 替换 ROM
    public static final String FORCE_ROM_BE_RE_INSTALL = "rom_should_be_re_install";

    // 是否应该使用第三方 ROM
    public static final String SHOULD_USE_THIRD_PARTY_ROM = "should_use_third_party_rom";

    // Server address (control port)
    public static final String SERVER_ADDRESS = "server_address";
    public static final String DEFAULT_SERVER_ADDRESS = "127.0.0.1:8765";

    // ADB port for scrcpy connections
    public static final String ADB_PORT = "adb_port";
    public static final int DEFAULT_ADB_PORT = 5555;

    // Display mode: "scrcpy" or "legacy"
    public static final String DISPLAY_MODE = "display_mode";
    public static final String DISPLAY_MODE_SCRCPY = "scrcpy";
    public static final String DISPLAY_MODE_LEGACY = "legacy";

    public static boolean getBooleanConfig(Context context,  String key, boolean fallback) {
        return getPref(context).getBoolean(key, fallback);
    }

    @SuppressLint("ApplySharedPref")
    public static void setBooleanConfig(Context context, String key, boolean value) {
        getPref(context).edit().putBoolean(key, value).commit();
    }

    public static String getStringConfig(Context context, String key, String fallback) {
        return getPref(context).getString(key, fallback);
    }

    @SuppressLint("ApplySharedPref")
    public static void setStringConfig(Context context, String key, String value) {
        getPref(context).edit().putString(key, value).commit();
    }

    public static int getIntConfig(Context context, String key, int fallback) {
        return getPref(context).getInt(key, fallback);
    }

    @SuppressLint("ApplySharedPref")
    public static void setIntConfig(Context context, String key, int value) {
        getPref(context).edit().putInt(key, value).commit();
    }

    private static SharedPreferences getPref(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}
