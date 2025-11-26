/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Manages the twoyi server binary and connections
 */
public class ServerManager {
    private static final String TAG = "ServerManager";
    private static final String SERVER_BINARY_NAME = "twoyi-server";

    private static Process serverProcess;

    /**
     * Extract the server binary from assets to the app's files directory
     */
    public static File extractServerBinary(Context context) throws IOException {
        File serverBinary = new File(context.getFilesDir(), SERVER_BINARY_NAME);
        
        AssetManager assets = context.getAssets();
        try (InputStream in = new BufferedInputStream(assets.open(SERVER_BINARY_NAME));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(serverBinary))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
        }
        
        // Make executable
        if (!serverBinary.setExecutable(true)) {
            throw new IOException("Failed to make server binary executable");
        }
        
        Log.i(TAG, "Server binary extracted to: " + serverBinary.getAbsolutePath());
        return serverBinary;
    }

    /**
     * Start the local server with the given rootfs path and bind address
     */
    public static void startServer(Context context, String bindAddress, int width, int height) throws IOException {
        // Stop any existing server
        stopServer();

        // Extract server binary
        File serverBinary = extractServerBinary(context);
        
        // Get rootfs path
        File rootfsDir = RomManager.getRootfsDir(context);
        if (!rootfsDir.exists()) {
            throw new IOException("Rootfs directory does not exist: " + rootfsDir);
        }

        // Ensure boot files exist
        RomManager.ensureBootFiles(context);

        // Build command
        ProcessBuilder pb = new ProcessBuilder(
            serverBinary.getAbsolutePath(),
            "--rootfs", rootfsDir.getAbsolutePath(),
            "--bind", bindAddress,
            "--width", String.valueOf(width),
            "--height", String.valueOf(height)
        );
        
        pb.directory(context.getFilesDir());
        pb.redirectErrorStream(true);
        
        // Start the process
        serverProcess = pb.start();
        
        // Log output in background
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(serverProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, "Server: " + line);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading server output", e);
            }
        }, "server-output").start();
        
        Log.i(TAG, "Server process started");
    }

    /**
     * Stop the running server
     */
    public static void stopServer() {
        if (serverProcess != null) {
            serverProcess.destroy();
            try {
                serverProcess.waitFor();
            } catch (InterruptedException ignored) {
            }
            serverProcess = null;
            Log.i(TAG, "Server stopped");
        }
    }

    /**
     * Check if the server is running
     */
    public static boolean isServerRunning() {
        return serverProcess != null && serverProcess.isAlive();
    }

    /**
     * Test connection to a server at the given address
     */
    public static boolean testConnection(String address) {
        String[] parts = address.split(":");
        if (parts.length != 2) {
            return false;
        }
        
        String host = parts[0];
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String response = reader.readLine();
            return response != null && response.contains("status");
        } catch (IOException e) {
            Log.e(TAG, "Connection test failed", e);
            return false;
        }
    }

    /**
     * Send a touch event to the server
     */
    public static void sendTouchEvent(PrintWriter writer, int action, int pointerId, float x, float y, float pressure) {
        String event = String.format(
            "{\"type\":\"touch\",\"action\":%d,\"pointer_id\":%d,\"x\":%.1f,\"y\":%.1f,\"pressure\":%.1f}\n",
            action, pointerId, x, y, pressure
        );
        writer.print(event);
        writer.flush();
    }

    /**
     * Send a key event to the server
     */
    public static void sendKeyEvent(PrintWriter writer, int keycode) {
        String event = String.format("{\"type\":\"key\",\"keycode\":%d}\n", keycode);
        writer.print(event);
        writer.flush();
    }
}
