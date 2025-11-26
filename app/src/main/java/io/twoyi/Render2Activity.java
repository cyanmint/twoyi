/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.cleveroad.androidmanimation.LoadingAnimationView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.AppKV;
import io.twoyi.utils.LogEvents;
import io.twoyi.utils.NavUtils;
import io.twoyi.utils.RomManager;

/**
 * @author weishu
 * @date 2021/10/20.
 */
public class Render2Activity extends Activity implements View.OnTouchListener {

    private static final String TAG = "Render2Activity";

    private static final String MODE_LOCAL = "local";
    private static final String MODE_REMOTE = "remote";
    private static final String MODE_SERVER = "server";

    private SurfaceView mSurfaceView;

    private ViewGroup mRootView;
    private LoadingAnimationView mLoadingView;
    private TextView mLoadingText;
    private View mLoadingLayout;
    private View mBootLogView;

    private final AtomicBoolean mIsExtracting = new AtomicBoolean(false);

    private String mMode = MODE_LOCAL;
    private String mServerAddress = null;
    private Socket mRemoteSocket = null;
    private PrintWriter mRemoteWriter = null;
    private BufferedReader mRemoteReader = null;
    private boolean mRemoteConnected = false;

    private final SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            Surface surface = holder.getSurface();
            WindowManager windowManager = getWindowManager();
            Display defaultDisplay = windowManager.getDefaultDisplay();
            DisplayMetrics displayMetrics = new DisplayMetrics();
            defaultDisplay.getRealMetrics(displayMetrics);

            float xdpi = displayMetrics.xdpi;
            float ydpi = displayMetrics.ydpi;

            if (MODE_LOCAL.equals(mMode) || MODE_SERVER.equals(mMode)) {
                Renderer.init(surface, RomManager.getLoaderPath(getApplicationContext()), xdpi, ydpi, (int) getBestFps());
            } else if (MODE_REMOTE.equals(mMode)) {
                // In remote mode, only initialize display without spawning container
                // The container is managed by the remote server
                Renderer.initDisplayOnly(surface, xdpi, ydpi, (int) getBestFps());
            }

            Log.i(TAG, "surfaceCreated, mode: " + mMode);
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            Surface surface = holder.getSurface();
            // Reset window for all modes
            Renderer.resetWindow(surface, 0, 0, mSurfaceView.getWidth(), mSurfaceView.getHeight());
            Log.i(TAG, "surfaceChanged: " + mSurfaceView.getWidth() + "x" + mSurfaceView.getHeight());
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            // Remove window for all modes
            Renderer.removeWindow(holder.getSurface());
            Log.i(TAG, "surfaceDestroyed!");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Get mode from intent
        mMode = getIntent().getStringExtra("mode");
        if (mMode == null) {
            mMode = MODE_LOCAL;
        }
        mServerAddress = getIntent().getStringExtra("server_address");

        boolean started = TwoyiStatusManager.getInstance().isStarted();
        Log.i(TAG, "onCreate: " + savedInstanceState + " isStarted: " + started + " mode: " + mMode);

        if (started && MODE_LOCAL.equals(mMode)) {
            // we have been started, but WTF we are onCreate again? just reboot ourself.
            finish();
            RomManager.reboot(this);
            return;
        }

        // reset state
        if (MODE_LOCAL.equals(mMode) || MODE_SERVER.equals(mMode)) {
            TwoyiStatusManager.getInstance().reset();
        }

        NavUtils.hideNavigation(getWindow());

        super.onCreate(savedInstanceState);

        setContentView(R.layout.ac_render);
        mRootView = findViewById(R.id.root);

        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(mSurfaceCallback);

        mLoadingLayout = findViewById(R.id.loadingLayout);
        mLoadingView = findViewById(R.id.loading);
        mLoadingText = findViewById(R.id.loadingText);
        mBootLogView = findViewById(R.id.bootlog);

        mLoadingLayout.setVisibility(View.VISIBLE);
        mLoadingView.startAnimation();

        if (MODE_REMOTE.equals(mMode)) {
            // Connect to remote server
            connectToRemoteServer();
        } else {
            UITips.checkForAndroid12(this, this::bootSystem);
        }

        mSurfaceView.setOnTouchListener(this);
    }

    private void connectToRemoteServer() {
        if (mServerAddress == null || mServerAddress.isEmpty()) {
            Toast.makeText(this, R.string.invalid_server_address, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mLoadingText.setText(R.string.connecting_to_server);

        new Thread(() -> {
            try {
                String[] parts = mServerAddress.split(":");
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);

                mRemoteSocket = new Socket();
                mRemoteSocket.connect(new java.net.InetSocketAddress(host, port), 10000); // 10 second connection timeout
                mRemoteSocket.setSoTimeout(30000); // 30 second read timeout
                mRemoteWriter = new PrintWriter(new OutputStreamWriter(mRemoteSocket.getOutputStream()), true);
                mRemoteReader = new BufferedReader(new InputStreamReader(mRemoteSocket.getInputStream()));

                // Send ping to verify connection
                mRemoteWriter.println("{\"type\":\"Ping\"}");
                String response = mRemoteReader.readLine();
                
                if (response != null && response.contains("Pong")) {
                    mRemoteConnected = true;
                    
                    // Send start container command
                    mRemoteWriter.println("{\"type\":\"StartContainer\"}");
                    response = mRemoteReader.readLine();
                    Log.i(TAG, "StartContainer response: " + response);

                    runOnUiThread(() -> {
                        Toast.makeText(this, R.string.connection_success, Toast.LENGTH_SHORT).show();
                        mRootView.addView(mSurfaceView, 0);
                        showBootingProcedure();
                    });
                } else {
                    throw new Exception("Invalid server response");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to connect to server", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.connection_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }, "connect-server").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeRemoteConnection();
    }

    private void closeRemoteConnection() {
        try {
            if (mRemoteWriter != null) {
                mRemoteWriter.close();
            }
            if (mRemoteReader != null) {
                mRemoteReader.close();
            }
            if (mRemoteSocket != null) {
                mRemoteSocket.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing remote connection", e);
        }
        mRemoteConnected = false;
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        Log.i(TAG, "onRestoreInstanceState: " + savedInstanceState);

        // we don't support state restore, just reboot.
        finish();
        RomManager.reboot(this);
    }

    private void bootSystem() {
        boolean romExist = RomManager.romExist(this);
        boolean factoryRomUpdated = RomManager.needsUpgrade(this);
        boolean forceInstall = AppKV.getBooleanConfig(getApplicationContext(), AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        boolean use3rdRom = AppKV.getBooleanConfig(getApplicationContext(), AppKV.SHOULD_USE_THIRD_PARTY_ROM, false);

        boolean shouldExtractRom = !romExist || forceInstall || (!use3rdRom && factoryRomUpdated);

        if (shouldExtractRom) {
            Log.i(TAG, "extracting rom...");

            showTipsForFirstBoot();

            new Thread(() -> {
                mIsExtracting.set(true);
                RomManager.extractRootfs(getApplicationContext(), romExist, factoryRomUpdated, forceInstall, use3rdRom);
                mIsExtracting.set(false);

                RomManager.initRootfs(getApplicationContext());

                runOnUiThread(() -> {
                    mRootView.addView(mSurfaceView, 0);
                    showBootingProcedure();
                });
            }, "extract-rom").start();
        } else {
            mRootView.addView(mSurfaceView, 0);
            showBootingProcedure();
        }
    }

    private void showTipsForFirstBoot() {
        mLoadingText.setText(R.string.extracting_tips);
        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips);
            }
        }, 5000);

        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips2);
            }
        }, 10 * 1000);

        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips3);
            }
        }, 15 * 1000);
    }

    private void showBootingProcedure() {
        // mLoadingText.setText(R.string.booting_tips);
        mLoadingText.setVisibility(View.GONE);
        mBootLogView.setVisibility(View.VISIBLE);
        new Thread(() -> {

            if (true) {
                boolean success = false;
                try {
                    success = TwoyiStatusManager.getInstance().waitBoot(15, TimeUnit.SECONDS);
                } catch (Throwable ignored) {
                }

                if (!success && !MODE_REMOTE.equals(mMode)) {
                    LogEvents.trackBootFailure(getApplicationContext());

                    runOnUiThread(() -> Toast.makeText(getApplicationContext(), R.string.boot_failed, Toast.LENGTH_SHORT).show());

                    // waiting for track
                    SystemClock.sleep(3000);

                    finish();
                    System.exit(0);
                    return;
                }
            }

            runOnUiThread(() -> {
                mLoadingView.stopAnimation();
                mLoadingLayout.setVisibility(View.GONE);
            });
        }, "waiting-boot").start();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            NavUtils.hideNavigation(getWindow());
        }

        // Update global visibility.
        TwoyiStatusManager.getInstance().updateVisibility(hasFocus);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (MODE_REMOTE.equals(mMode) && mRemoteConnected) {
            sendTouchEventToServer(event);
        } else {
            Renderer.handleTouch(event);
        }
        return true;
    }

    private void sendTouchEventToServer(MotionEvent event) {
        if (mRemoteWriter == null) return;

        new Thread(() -> {
            try {
                int action;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_POINTER_DOWN:
                        action = 0;
                        break;
                    case MotionEvent.ACTION_UP:
                        action = 1;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        action = 2;
                        break;
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_POINTER_UP:
                        action = 3;
                        break;
                    default:
                        return;
                }

                int pointerIndex = event.getActionIndex();
                int pointerId = event.getPointerId(pointerIndex);
                float x = event.getX(pointerIndex);
                float y = event.getY(pointerIndex);
                float pressure = event.getPressure(pointerIndex);

                String json = String.format(
                    "{\"type\":\"TouchEvent\",\"action\":%d,\"x\":%.2f,\"y\":%.2f,\"pointer_id\":%d,\"pressure\":%.2f}",
                    action, x, y, pointerId, pressure
                );
                mRemoteWriter.println(json);
            } catch (Exception e) {
                Log.e(TAG, "Error sending touch event", e);
            }
        }).start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyDown: " + keyCode);
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // TODO: 2021/10/26 Add Volume control
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // super.onBackPressed();
        if (MODE_REMOTE.equals(mMode) && mRemoteConnected) {
            sendKeyEventToServer(KeyEvent.KEYCODE_HOME, true);
            sendKeyEventToServer(KeyEvent.KEYCODE_HOME, false);
        } else {
            Renderer.sendKeycode(KeyEvent.KEYCODE_HOME);
        }
    }

    private void sendKeyEventToServer(int keycode, boolean pressed) {
        if (mRemoteWriter == null) return;

        new Thread(() -> {
            try {
                String json = String.format(
                    "{\"type\":\"KeyEvent\",\"keycode\":%d,\"pressed\":%b}",
                    keycode, pressed
                );
                mRemoteWriter.println(json);
            } catch (Exception e) {
                Log.e(TAG, "Error sending key event", e);
            }
        }).start();
    }

    private float getBestFps() {
        WindowManager windowManager = getWindowManager();
        Display defaultDisplay = windowManager.getDefaultDisplay();
        Display.Mode[] supportedModes = defaultDisplay.getSupportedModes();
        float fps = 45;
        for (Display.Mode supportedMode : supportedModes) {
            float refreshRate = supportedMode.getRefreshRate();
            if (refreshRate > fps) {
                // fps = refreshRate;
            }
        }

        Log.w(TAG, "current fps: " + fps);
        return fps;
    }
}
