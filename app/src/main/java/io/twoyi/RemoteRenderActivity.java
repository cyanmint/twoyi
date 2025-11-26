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
 */
public class RemoteRenderActivity extends Activity implements View.OnTouchListener {

    private static final String TAG = "RemoteRenderActivity";

    private ViewGroup mRootView;
    private LoadingAnimationView mLoadingView;
    private TextView mLoadingText;
    private View mLoadingLayout;
    private View mContentView;

    private Socket mSocket;
    private PrintWriter mWriter;
    private BufferedReader mReader;
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private String mServerAddress;

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

        // Create a simple view to show connection status and receive touch events
        mContentView = new View(this) {
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                canvas.drawColor(android.graphics.Color.BLACK);
                
                if (mConnected.get()) {
                    android.graphics.Paint paint = new android.graphics.Paint();
                    paint.setColor(android.graphics.Color.WHITE);
                    paint.setTextSize(48);
                    paint.setTextAlign(android.graphics.Paint.Align.CENTER);
                    canvas.drawText("Connected to: " + mServerAddress, 
                        getWidth() / 2f, getHeight() / 2f - 50, paint);
                    canvas.drawText("Touch to send input events", 
                        getWidth() / 2f, getHeight() / 2f + 50, paint);
                }
            }
        };
        mContentView.setOnTouchListener(this);

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
                    int width = info.optInt("width", 1080);
                    int height = info.optInt("height", 1920);
                    String status = info.optString("status", "unknown");
                    
                    Log.i(TAG, "Server: " + width + "x" + height + ", status: " + status);
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
