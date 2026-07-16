// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Starts a persistent adb server that listens on a user-configured socket
 * (bind address + port, e.g. {@code 127.0.0.1:8555}) and bridges it to the
 * container's guest adbd (running on {@code localhost:22122}).
 *
 * <p>This lets an external adb client (for example the adb binary shipped with
 * Termux) reach the container's adbd without going through the in-app install
 * flow, e.g. {@code adb -P 8555 shell} or {@code adb connect 127.0.0.1:8555}.
 */
public final class AdbServer {

    private static final String TAG = "AdbServer";

    /** The guest adbd endpoint that the container exposes internally. */
    private static final String GUEST_TARGET = "localhost:22122";

    private AdbServer() {
    }

    /**
     * Starts the adb listen server only when the active profile has an adb
     * listen address configured; otherwise does nothing.
     */
    public static void startIfConfigured(Context context) {
        String listenAddress = ProfileSettings.getAdbListenAddress(context);
        if (TextUtils.isEmpty(listenAddress)) {
            return;
        }
        start(context, listenAddress.trim());
    }

    /**
     * Normalises a user-provided listen address into an adb {@code tcp:} socket
     * specification.  Accepts either {@code port} or {@code host:port}.
     *
     * @return the socket spec (e.g. {@code tcp:127.0.0.1:8555}) or {@code null}
     *         when the input is not a valid listen address.
     */
    public static String toSocketSpec(String listenAddress) {
        if (listenAddress == null) {
            return null;
        }
        String value = listenAddress.trim();
        if (TextUtils.isEmpty(value)) {
            return null;
        }

        String portPart = value;
        int colon = value.lastIndexOf(':');
        if (colon >= 0) {
            portPart = value.substring(colon + 1);
        }

        int port;
        try {
            port = Integer.parseInt(portPart);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }

        return "tcp:" + value;
    }

    /**
     * Starts an adb server listening on {@code listenAddress} and connects it to
     * the guest adbd so external clients can control the container.
     */
    public static void start(Context context, String listenAddress) {
        String socketSpec = toSocketSpec(listenAddress);
        if (socketSpec == null) {
            Log.w(TAG, "invalid adb listen address, skipping: " + listenAddress);
            return;
        }

        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        String adbPath = nativeLibraryDir + File.separator + "libadb.so";

        String envPath = context.getCacheDir().getAbsolutePath();
        String envCmd = String.format(Locale.US, "export TMPDIR=%s;export HOME=%s;", envPath, envPath);

        // Start a long-lived adb server bound to the configured socket. Uses
        // `nodaemon` so it runs in the foreground of the shell job (submitted
        // asynchronously) instead of forking a background daemon we can't track.
        String serverCommand = String.format(Locale.US, "%s -L %s nodaemon server", adbPath, socketSpec);
        ShellUtil.newSh().newJob().add(envCmd).add(serverCommand).submit();

        // Connect the server to the guest adbd so `adb ... devices` shows the
        // container without the client needing to know the internal endpoint.
        String connectCommand = String.format(Locale.US, "%s -L %s connect %s", adbPath, socketSpec, GUEST_TARGET);
        ShellUtil.newSh().newJob().add(envCmd).add(connectCommand)
                .to(new ArrayList<>(), new ArrayList<>()).submit();

        Log.w(TAG, "adb listen server started on " + listenAddress);
    }
}
