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
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the twoyi server binary and connections
 */
public class ServerManager {
    private static final String TAG = "ServerManager";
    private static final String SERVER_BINARY_NAME = "twoyi-server";

    private static Process serverProcess;
    private static final List<ServerOutputListener> outputListeners = new ArrayList<>();
    private static final List<String> serverLog = new ArrayList<>();
    private static final int MAX_LOG_LINES = 500;

    /**
     * Interface for receiving server output
     */
    public interface ServerOutputListener {
        void onServerOutput(String line);
    }

    /**
     * Add a listener for server output
     */
    public static void addOutputListener(ServerOutputListener listener) {
        synchronized (outputListeners) {
            if (!outputListeners.contains(listener)) {
                outputListeners.add(listener);
            }
        }
    }

    /**
     * Remove a listener for server output
     */
    public static void removeOutputListener(ServerOutputListener listener) {
        synchronized (outputListeners) {
            outputListeners.remove(listener);
        }
    }

    /**
     * Get the current server log
     */
    public static List<String> getServerLog() {
        synchronized (serverLog) {
            return new ArrayList<>(serverLog);
        }
    }

    /**
     * Clear the server log
     */
    public static void clearServerLog() {
        synchronized (serverLog) {
            serverLog.clear();
        }
    }

    private static void notifyOutputListeners(String line) {
        synchronized (serverLog) {
            serverLog.add(line);
            while (serverLog.size() > MAX_LOG_LINES) {
                serverLog.remove(0);
            }
        }
        synchronized (outputListeners) {
            for (ServerOutputListener listener : outputListeners) {
                try {
                    listener.onServerOutput(line);
                } catch (Exception e) {
                    Log.e(TAG, "Error notifying listener", e);
                }
            }
        }
    }

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
        
        // Clear previous log
        clearServerLog();

        // Extract server binary
        File serverBinary = extractServerBinary(context);
        
        // Get rootfs path
        File rootfsDir = RomManager.getRootfsDir(context);
        if (!rootfsDir.exists()) {
            throw new IOException("Rootfs directory does not exist: " + rootfsDir);
        }

        // Get loader path
        String loaderPath = RomManager.getLoaderPath(context);

        // Ensure boot files exist
        RomManager.ensureBootFiles(context);

        // Build command with verbose mode and loader
        ProcessBuilder pb = new ProcessBuilder(
            serverBinary.getAbsolutePath(),
            "--rootfs", rootfsDir.getAbsolutePath(),
            "--bind", bindAddress,
            "--width", String.valueOf(width),
            "--height", String.valueOf(height),
            "--loader", loaderPath,
            "--verbose"
        );
        
        pb.directory(context.getFilesDir());
        pb.redirectErrorStream(true);
        
        // Start the process
        serverProcess = pb.start();
        
        // Log output in background and notify listeners
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(serverProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, "Server: " + line);
                    notifyOutputListeners(line);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading server output", e);
                notifyOutputListeners("Error reading server output: " + e.getMessage());
            }
            notifyOutputListeners("Server process ended");
        }, "server-output").start();
        
        Log.i(TAG, "Server process started");
        notifyOutputListeners("Server process started");
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
        
        try (Socket socket = new Socket(host, port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            socket.setSoTimeout(5000);
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
