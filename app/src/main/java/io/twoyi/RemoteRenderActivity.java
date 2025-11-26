/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.app.Activity;
import android.graphics.Bitmap;
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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.AppKV;
import io.twoyi.utils.NavUtils;

/**
 * Activity for connecting to and rendering from a remote twoyi server
 * Receives framebuffer data over TCP and displays it
 */
public class RemoteRenderActivity extends Activity implements View.OnTouchListener {

    private static final String TAG = "RemoteRenderActivity";
    private static final byte[] FRAME_HEADER = "FRAME".getBytes();

    private ViewGroup mRootView;
    private LoadingAnimationView mLoadingView;
    private TextView mLoadingText;
    private View mLoadingLayout;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;

    private Socket mSocket;
    private PrintWriter mWriter;
    private DataInputStream mDataReader;
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final AtomicBoolean mReceiving = new AtomicBoolean(false);
    private String mServerAddress;
    private int mServerWidth = 1080;
    private int mServerHeight = 1920;
    private String mServerStatus = "unknown";
    private boolean mSetupMode = false;
    private boolean mStreamingEnabled = false;

    private Bitmap mFrameBitmap;
    private final Object mBitmapLock = new Object();

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

                Log.i(TAG, "Connecting to " + host + ":" + port);
                mSocket = new Socket(host, port);
                mSocket.setTcpNoDelay(true);
                
                mWriter = new PrintWriter(mSocket.getOutputStream(), true);
                mDataReader = new DataInputStream(mSocket.getInputStream());

                // Read initial server info (first line is JSON, terminated by newline)
                StringBuilder sb = new StringBuilder();
                int b;
                while ((b = mDataReader.read()) != -1 && b != '\n') {
                    sb.append((char) b);
                }
                String serverInfo = sb.toString();
                Log.i(TAG, "Server info: " + serverInfo);

                if (serverInfo.length() > 0) {
                    JSONObject info = new JSONObject(serverInfo);
                    mServerWidth = info.optInt("width", 1080);
                    mServerHeight = info.optInt("height", 1920);
                    mServerStatus = info.optString("status", "unknown");
                    mSetupMode = info.optBoolean("setup_mode", false);
                    mStreamingEnabled = info.optBoolean("streaming", false);
                    
                    Log.i(TAG, "Server: " + mServerWidth + "x" + mServerHeight + 
                          ", status: " + mServerStatus + ", streaming: " + mStreamingEnabled);
                }

                // Create bitmap for framebuffer
                synchronized (mBitmapLock) {
                    mFrameBitmap = Bitmap.createBitmap(mServerWidth, mServerHeight, Bitmap.Config.ARGB_8888);
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

                // Start receiving frames
                if (mStreamingEnabled) {
                    startFrameReceiver();
                } else {
                    // Show status message if no streaming
                    showStatusMessage();
                }

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

    private void showStatusMessage() {
        new Thread(() -> {
            while (mConnected.get() && !mStreamingEnabled) {
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
                            canvas.drawText("✓ Connected", centerX, centerY - 100, paint);
                            
                            paint.setColor(Color.WHITE);
                            canvas.drawText("Server: " + mServerAddress, centerX, centerY - 30, paint);
                            
                            paint.setTextSize(32);
                            paint.setColor(Color.LTGRAY);
                            canvas.drawText("Resolution: " + mServerWidth + "x" + mServerHeight, centerX, centerY + 40, paint);
                            canvas.drawText("Status: " + mServerStatus, centerX, centerY + 80, paint);
                            
                            if (mSetupMode) {
                                paint.setColor(Color.YELLOW);
                                canvas.drawText("(Setup Mode)", centerX, centerY + 120, paint);
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
        }, "status-display").start();
    }

    private void startFrameReceiver() {
        mReceiving.set(true);
        new Thread(() -> {
            byte[] headerBuf = new byte[5];
            byte[] sizeBuf = new byte[12]; // width(4) + height(4) + length(4)
            
            while (mReceiving.get() && mConnected.get()) {
                try {
                    // Read frame header
                    mDataReader.readFully(headerBuf);
                    
                    // Verify header
                    boolean validHeader = true;
                    for (int i = 0; i < FRAME_HEADER.length; i++) {
                        if (headerBuf[i] != FRAME_HEADER[i]) {
                            validHeader = false;
                            break;
                        }
                    }
                    
                    if (!validHeader) {
                        Log.e(TAG, "Invalid frame header received, connection may be corrupted");
                        break; // Exit the loop and close connection
                    }
                    
                    // Read frame dimensions and length
                    mDataReader.readFully(sizeBuf);
                    ByteBuffer bb = ByteBuffer.wrap(sizeBuf).order(ByteOrder.LITTLE_ENDIAN);
                    int width = bb.getInt();
                    int height = bb.getInt();
                    int length = bb.getInt();
                    
                    // Read frame data
                    byte[] frameData = new byte[length];
                    mDataReader.readFully(frameData);
                    
                    // Update bitmap
                    synchronized (mBitmapLock) {
                        if (mFrameBitmap != null && 
                            mFrameBitmap.getWidth() == width && 
                            mFrameBitmap.getHeight() == height) {
                            
                            // Convert RGBA bytes to bitmap
                            ByteBuffer buffer = ByteBuffer.wrap(frameData);
                            mFrameBitmap.copyPixelsFromBuffer(buffer);
                        }
                    }
                    
                    // Draw to surface
                    if (mSurfaceHolder.getSurface().isValid()) {
                        Canvas canvas = mSurfaceHolder.lockCanvas();
                        if (canvas != null) {
                            try {
                                synchronized (mBitmapLock) {
                                    if (mFrameBitmap != null) {
                                        // Scale bitmap to fit surface
                                        canvas.drawBitmap(mFrameBitmap, null, 
                                            new android.graphics.Rect(0, 0, canvas.getWidth(), canvas.getHeight()), 
                                            null);
                                    }
                                }
                            } finally {
                                mSurfaceHolder.unlockCanvasAndPost(canvas);
                            }
                        }
                    }
                    
                } catch (IOException e) {
                    if (mReceiving.get()) {
                        Log.e(TAG, "Error receiving frame", e);
                    }
                    break;
                }
            }
            Log.i(TAG, "Frame receiver stopped");
        }, "frame-receiver").start();
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
            
            // Scale touch coordinates to server resolution
            float scaleX = (float) mServerWidth / v.getWidth();
            float scaleY = (float) mServerHeight / v.getHeight();
            float x = event.getX(pointerIndex) * scaleX;
            float y = event.getY(pointerIndex) * scaleY;
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
        mReceiving.set(false);
        
        synchronized (mBitmapLock) {
            if (mFrameBitmap != null) {
                mFrameBitmap.recycle();
                mFrameBitmap = null;
            }
        }
        
        try {
            if (mWriter != null) mWriter.close();
            if (mDataReader != null) mDataReader.close();
            if (mSocket != null) mSocket.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing connection", e);
        }
    }
}
