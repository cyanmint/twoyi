/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.cleveroad.androidmanimation.LoadingAnimationView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.AppKV;
import io.twoyi.utils.NavUtils;

/**
 * Activity for connecting to and rendering from a remote twoyi server
 * 
 * Note: Currently this activity only provides input forwarding to the server.
 * Full screen rendering over TCP is not yet implemented - the server handles
 * display through its local OpenGL context.
 */
public class RemoteRenderActivity extends Activity implements View.OnTouchListener {

    private static final String TAG = "RemoteRenderActivity";

    private ViewGroup mRootView;
    private LoadingAnimationView mLoadingView;
    private TextView mLoadingText;
    private View mLoadingLayout;
    private StatusView mContentView;

    private Socket mSocket;
    private PrintWriter mWriter;
    private BufferedReader mReader;
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private String mServerAddress;
    private int mServerWidth = 1080;
    private int mServerHeight = 1920;
    private String mServerStatus = "unknown";
    private boolean mSetupMode = false;

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

        // Create status view
        mContentView = new StatusView(this);
        mContentView.setOnTouchListener(this);

        connectToServer();
    }

    private class StatusView extends View {
        private final android.graphics.Paint mPaint;
        private final android.graphics.Paint mSmallPaint;
        
        public StatusView(android.content.Context context) {
            super(context);
            mPaint = new android.graphics.Paint();
            mPaint.setColor(android.graphics.Color.WHITE);
            mPaint.setTextSize(48);
            mPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
            mPaint.setAntiAlias(true);
            
            mSmallPaint = new android.graphics.Paint();
            mSmallPaint.setColor(android.graphics.Color.LTGRAY);
            mSmallPaint.setTextSize(32);
            mSmallPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
            mSmallPaint.setAntiAlias(true);
        }
        
        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(android.graphics.Color.BLACK);
            
            if (mConnected.get()) {
                float centerX = getWidth() / 2f;
                float centerY = getHeight() / 2f;
                
                // Connection status
                mPaint.setColor(android.graphics.Color.GREEN);
                canvas.drawText("✓ Connected", centerX, centerY - 150, mPaint);
                
                mPaint.setColor(android.graphics.Color.WHITE);
                canvas.drawText("Server: " + mServerAddress, centerX, centerY - 80, mPaint);
                
                // Server info
                mSmallPaint.setColor(android.graphics.Color.LTGRAY);
                canvas.drawText("Resolution: " + mServerWidth + "x" + mServerHeight, centerX, centerY, mSmallPaint);
                canvas.drawText("Status: " + mServerStatus, centerX, centerY + 40, mSmallPaint);
                
                if (mSetupMode) {
                    mSmallPaint.setColor(android.graphics.Color.YELLOW);
                    canvas.drawText("(Setup Mode - Container not running)", centerX, centerY + 80, mSmallPaint);
                }
                
                // Instructions
                mSmallPaint.setColor(android.graphics.Color.GRAY);
                canvas.drawText("Touch events are being forwarded to the server", centerX, centerY + 160, mSmallPaint);
                canvas.drawText("View server output in Settings > Server Console", centerX, centerY + 200, mSmallPaint);
            }
        }
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

                Log.i(TAG, "Connecting to " + host + ":" + port);
                mSocket = new Socket(host, port);
                mSocket.setSoTimeout(30000);
                
                mWriter = new PrintWriter(mSocket.getOutputStream(), true);
                mReader = new BufferedReader(new InputStreamReader(mSocket.getInputStream()));

                // Read initial server info
                String serverInfo = mReader.readLine();
                Log.i(TAG, "Server info: " + serverInfo);

                if (serverInfo != null) {
                    JSONObject info = new JSONObject(serverInfo);
                    mServerWidth = info.optInt("width", 1080);
                    mServerHeight = info.optInt("height", 1920);
                    mServerStatus = info.optString("status", "unknown");
                    mSetupMode = info.optBoolean("setup_mode", false);
                    
                    Log.i(TAG, "Server: " + mServerWidth + "x" + mServerHeight + ", status: " + mServerStatus);
                }

                mConnected.set(true);

                runOnUiThread(() -> {
                    mLoadingView.stopAnimation();
                    mLoadingLayout.setVisibility(View.GONE);
                    mRootView.addView(mContentView, 0, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                    mContentView.invalidate();
                    Toast.makeText(this, R.string.server_connected, Toast.LENGTH_SHORT).show();
                });

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

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (!mConnected.get() || mWriter == null) {
            return true;
        }

        try {
            int action = event.getActionMasked();
            int pointerIndex = event.getActionIndex();
            int pointerId = event.getPointerId(pointerIndex);
            float x = event.getX(pointerIndex);
            float y = event.getY(pointerIndex);
            float pressure = event.getPressure(pointerIndex);

            String json = String.format(Locale.US,
                "{\"type\":\"touch\",\"action\":%d,\"pointer_id\":%d,\"x\":%.1f,\"y\":%.1f,\"pressure\":%.1f}",
                action, pointerId, x, y, pressure);
            
            mWriter.println(json);
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending touch event", e);
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mConnected.get() && mWriter != null) {
            try {
                String json = String.format(Locale.US, "{\"type\":\"key\",\"keycode\":%d}", keyCode);
                mWriter.println(json);
            } catch (Exception e) {
                Log.e(TAG, "Error sending key event", e);
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (mConnected.get() && mWriter != null) {
            try {
                String json = String.format(Locale.US, "{\"type\":\"key\",\"keycode\":%d}", KeyEvent.KEYCODE_BACK);
                mWriter.println(json);
            } catch (Exception e) {
                Log.e(TAG, "Error sending back key", e);
            }
        } else {
            super.onBackPressed();
        }
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
            if (mWriter != null) mWriter.close();
            if (mReader != null) mReader.close();
            if (mSocket != null) mSocket.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing connection", e);
        }
    }
}
