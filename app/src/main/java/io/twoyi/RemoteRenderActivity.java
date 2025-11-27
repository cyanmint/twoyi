/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.cleveroad.androidmanimation.LoadingAnimationView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.AppKV;
import io.twoyi.utils.NavUtils;

/**
 * Activity for connecting to a remote twoyi server via scrcpy/ADB
 * Uses scrcpy protocol to connect to the container's adbd for graphics display
 */
public class RemoteRenderActivity extends Activity implements View.OnTouchListener {

    private static final String TAG = "RemoteRenderActivity";

    private ViewGroup mRootView;
    private LoadingAnimationView mLoadingView;
    private TextView mLoadingText;
    private View mLoadingLayout;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;

    private Socket mSocket;
    private PrintWriter mWriter;
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private String mServerAddress;
    private int mServerWidth = 1080;
    private int mServerHeight = 1920;
    private String mServerStatus = "unknown";
    private boolean mSetupMode = false;
    private boolean mScrcpyMode = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        NavUtils.hideNavigation(getWindow());
        super.onCreate(savedInstanceState);

        setContentView(R.layout.ac_render);
        mRootView = findViewById(R.id.root);

        mLoadingLayout = findViewById(R.id.loadingLayout);
        mLoadingView = findViewById(R.id.loading);
        mLoadingText = findViewById(R.id.loadingText);

        mLoadingLayout.setVisibility(View.VISIBLE);
        mLoadingView.startAnimation();

        // Get server address from intent or settings
        mServerAddress = getIntent().getStringExtra("server_address");
        if (mServerAddress == null || mServerAddress.isEmpty()) {
            mServerAddress = AppKV.getStringConfig(this, AppKV.SERVER_ADDRESS, AppKV.DEFAULT_SERVER_ADDRESS);
        }

        mLoadingText.setText(getString(R.string.server_connecting));

        // Create SurfaceView for rendering
        mSurfaceView = new SurfaceView(this);
        mSurfaceView.setOnTouchListener(this);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.i(TAG, "Surface created");
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Log.i(TAG, "Surface changed: " + width + "x" + height);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.i(TAG, "Surface destroyed");
            }
        });

        connectToServer();
    }

    private void connectToServer() {
        new Thread(() -> {
            try {
                String[] parts = mServerAddress.split(":");
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Invalid address format");
                }
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);

                Log.i(TAG, "Connecting to ADB server at " + host + ":" + port);
                mSocket = new Socket(host, port);
                mSocket.setTcpNoDelay(true);
                
                mWriter = new PrintWriter(mSocket.getOutputStream(), true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(mSocket.getInputStream()));

                // Read initial server info (first line may be JSON if adbd not ready)
                String serverInfo = reader.readLine();
                Log.i(TAG, "Server response: " + serverInfo);

                if (serverInfo != null && serverInfo.startsWith("{")) {
                    // JSON response means adbd is not yet available or error
                    JSONObject info = new JSONObject(serverInfo);
                    mServerWidth = info.optInt("width", 1080);
                    mServerHeight = info.optInt("height", 1920);
                    mServerStatus = info.optString("status", "unknown");
                    mSetupMode = info.optBoolean("setup_mode", false);
                    mScrcpyMode = info.optBoolean("scrcpy_mode", true);
                    
                    String error = info.optString("error", null);
                    if (error != null) {
                        Log.w(TAG, "Server reported error: " + error);
                        String message = info.optString("message", "Unknown error");
                        runOnUiThread(() -> {
                            mLoadingView.stopAnimation();
                            mLoadingText.setText("Waiting for container's ADB...\n" + message);
                        });
                        // Show status and wait
                        mConnected.set(true);
                        showScrcpyInstructions();
                        return;
                    }
                    
                    Log.i(TAG, "Server: " + mServerWidth + "x" + mServerHeight + 
                          ", status: " + mServerStatus + ", scrcpy_mode: " + mScrcpyMode);
                } else {
                    // Raw ADB protocol response - scrcpy should handle this
                    Log.i(TAG, "Received ADB protocol data, scrcpy can connect");
                }

                mConnected.set(true);

                runOnUiThread(() -> {
                    mLoadingView.stopAnimation();
                    mLoadingLayout.setVisibility(View.GONE);
                    mRootView.addView(mSurfaceView, 0, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                    Toast.makeText(this, R.string.server_connected, Toast.LENGTH_SHORT).show();
                });

                // Show scrcpy connection instructions
                showScrcpyInstructions();

            } catch (Exception e) {
                Log.e(TAG, "Failed to connect to server", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.server_connection_failed, e.getMessage()), 
                        Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "server-connect").start();
    }

    private void showScrcpyInstructions() {
        new Thread(() -> {
            while (mConnected.get()) {
                if (mSurfaceHolder.getSurface().isValid()) {
                    Canvas canvas = mSurfaceHolder.lockCanvas();
                    if (canvas != null) {
                        try {
                            canvas.drawColor(Color.BLACK);
                            
                            Paint paint = new Paint();
                            paint.setColor(Color.WHITE);
                            paint.setTextSize(48);
                            paint.setTextAlign(Paint.Align.CENTER);
                            paint.setAntiAlias(true);
                            
                            float centerX = canvas.getWidth() / 2f;
                            float centerY = canvas.getHeight() / 2f;
                            
                            paint.setColor(Color.GREEN);
                            canvas.drawText("✓ Connected to Server", centerX, centerY - 200, paint);
                            
                            paint.setColor(Color.WHITE);
                            paint.setTextSize(36);
                            canvas.drawText("ADB Address: " + mServerAddress, centerX, centerY - 130, paint);
                            
                            paint.setTextSize(32);
                            paint.setColor(Color.CYAN);
                            canvas.drawText("=== SCRCPY GRAPHICS MODE ===", centerX, centerY - 60, paint);
                            
                            paint.setColor(Color.LTGRAY);
                            paint.setTextSize(28);
                            canvas.drawText("Use scrcpy to connect to the container for graphics:", centerX, centerY, paint);
                            
                            paint.setColor(Color.YELLOW);
                            canvas.drawText("scrcpy --tcpip=" + mServerAddress, centerX, centerY + 50, paint);
                            
                            paint.setColor(Color.LTGRAY);
                            paint.setTextSize(24);
                            canvas.drawText("Resolution: " + mServerWidth + "x" + mServerHeight, centerX, centerY + 110, paint);
                            canvas.drawText("Status: " + mServerStatus, centerX, centerY + 145, paint);
                            
                            if (mSetupMode) {
                                paint.setColor(Color.YELLOW);
                                canvas.drawText("(Setup Mode)", centerX, centerY + 180, paint);
                            }
                        } finally {
                            mSurfaceHolder.unlockCanvasAndPost(canvas);
                        }
                    }
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "scrcpy-instructions").start();
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        // Touch events will be handled by scrcpy directly
        // This is just for basic interaction when scrcpy is not connected
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Key events will be handled by scrcpy directly
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // Allow back to exit the activity
        super.onBackPressed();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            NavUtils.hideNavigation(getWindow());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mConnected.set(false);
        
        try {
            if (mWriter != null) {
                mWriter.close();
            }
            if (mSocket != null && !mSocket.isClosed()) {
                mSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing socket", e);
        }
    }
}
