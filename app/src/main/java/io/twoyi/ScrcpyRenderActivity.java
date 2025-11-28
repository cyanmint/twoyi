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
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.cleveroad.androidmanimation.LoadingAnimationView;

import io.twoyi.utils.AppKV;
import io.twoyi.utils.NavUtils;
import io.twoyi.utils.ScrcpyClient;

/**
 * Activity for displaying the container via scrcpy.
 * 
 * This activity connects to the container's ADB port and uses the scrcpy
 * protocol to receive and display the video stream.
 */
public class ScrcpyRenderActivity extends Activity implements View.OnTouchListener, ScrcpyClient.ScrcpyListener {

    private static final String TAG = "ScrcpyRenderActivity";

    private ViewGroup mRootView;
    private LoadingAnimationView mLoadingView;
    private TextView mLoadingText;
    private View mLoadingLayout;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;

    private ScrcpyClient mScrcpyClient;
    private String mServerHost;
    private int mAdbPort;
    private int mVideoWidth;
    private int mVideoHeight;

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

        // Get ADB address from intent or settings (format: host:port)
        String adbAddress = getIntent().getStringExtra("adb_address");
        if (adbAddress == null || adbAddress.isEmpty()) {
            adbAddress = AppKV.getStringConfig(this, AppKV.ADB_ADDRESS, AppKV.DEFAULT_ADB_ADDRESS);
        }

        // Parse host and port from ADB address
        String[] parts = adbAddress.split(":");
        mServerHost = parts[0];
        mAdbPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 5556;

        mLoadingText.setText(getString(R.string.scrcpy_connecting, mServerHost, mAdbPort));

        // Create SurfaceView for rendering
        mSurfaceView = new SurfaceView(this);
        mSurfaceView.setOnTouchListener(this);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.i(TAG, "Surface created");
                connectToScrcpy();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Log.i(TAG, "Surface changed: " + width + "x" + height);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.i(TAG, "Surface destroyed");
                disconnectFromScrcpy();
            }
        });

        // Add surface view to layout
        mRootView.addView(mSurfaceView, 0, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void connectToScrcpy() {
        if (mScrcpyClient != null && mScrcpyClient.isConnected()) {
            return;
        }

        mScrcpyClient = new ScrcpyClient();
        mScrcpyClient.setListener(this);
        mScrcpyClient.setSurface(mSurfaceHolder.getSurface());
        mScrcpyClient.connect(mServerHost, mAdbPort);
    }

    private void disconnectFromScrcpy() {
        if (mScrcpyClient != null) {
            mScrcpyClient.disconnect();
            mScrcpyClient = null;
        }
    }

    // ScrcpyClient.ScrcpyListener implementation

    @Override
    public void onConnected(int width, int height, String deviceName) {
        mVideoWidth = width;
        mVideoHeight = height;
        Log.i(TAG, "Connected to: " + deviceName + " (" + width + "x" + height + ")");
        
        runOnUiThread(() -> {
            mLoadingText.setText(getString(R.string.scrcpy_connected, deviceName));
        });
    }

    @Override
    public void onDisconnected() {
        Log.i(TAG, "Disconnected from scrcpy");
        runOnUiThread(() -> {
            Toast.makeText(this, R.string.scrcpy_disconnected, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @Override
    public void onError(String message) {
        Log.e(TAG, "Scrcpy error: " + message);
        runOnUiThread(() -> {
            Toast.makeText(this, getString(R.string.scrcpy_error, message), Toast.LENGTH_LONG).show();
            // Show connection status on surface
            showConnectionError(message);
        });
    }

    @Override
    public void onFirstFrameReceived() {
        Log.i(TAG, "First frame received");
        runOnUiThread(() -> {
            mLoadingView.stopAnimation();
            mLoadingLayout.setVisibility(View.GONE);
        });
    }

    private void showConnectionError(String message) {
        if (mSurfaceHolder.getSurface().isValid()) {
            Canvas canvas = mSurfaceHolder.lockCanvas();
            if (canvas != null) {
                try {
                    canvas.drawColor(Color.BLACK);

                    Paint paint = new Paint();
                    paint.setColor(Color.RED);
                    paint.setTextSize(48);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setAntiAlias(true);

                    float centerX = canvas.getWidth() / 2f;
                    float centerY = canvas.getHeight() / 2f;

                    canvas.drawText("Connection Error", centerX, centerY - 50, paint);

                    paint.setColor(Color.WHITE);
                    paint.setTextSize(32);
                    canvas.drawText(message, centerX, centerY + 30, paint);

                    paint.setColor(Color.GRAY);
                    paint.setTextSize(28);
                    canvas.drawText("Host: " + mServerHost + ":" + mAdbPort, centerX, centerY + 100, paint);
                } finally {
                    mSurfaceHolder.unlockCanvasAndPost(canvas);
                }
            }
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (mScrcpyClient == null || !mScrcpyClient.isConnected()) {
            return true;
        }

        int action = event.getActionMasked();
        int x = (int) event.getX();
        int y = (int) event.getY();

        mScrcpyClient.sendTouchEvent(action, x, y, v.getWidth(), v.getHeight());
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mScrcpyClient != null && mScrcpyClient.isConnected()) {
            mScrcpyClient.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mScrcpyClient != null && mScrcpyClient.isConnected()) {
            mScrcpyClient.sendKeyEvent(KeyEvent.ACTION_UP, keyCode);
        }
        return super.onKeyUp(keyCode, event);
    }

    private long mLastBackPressTime = 0;
    private static final long DOUBLE_BACK_PRESS_INTERVAL = 2000; // 2 seconds

    @Override
    public void onBackPressed() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - mLastBackPressTime < DOUBLE_BACK_PRESS_INTERVAL) {
            // Double press - exit to settings
            super.onBackPressed();
        } else {
            // First press - send to container and show toast
            mLastBackPressTime = currentTime;
            if (mScrcpyClient != null && mScrcpyClient.isConnected()) {
                mScrcpyClient.sendKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
                mScrcpyClient.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK);
            }
            Toast.makeText(this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show();
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
        disconnectFromScrcpy();
    }
}
