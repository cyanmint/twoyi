/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.MenuItem;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

import io.twoyi.R;
import io.twoyi.utils.ServerManager;

/**
 * Activity to display server console output
 */
public class ServerConsoleActivity extends AppCompatActivity implements ServerManager.ServerOutputListener {

    private TextView mConsoleText;
    private ScrollView mScrollView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder mLogBuilder = new StringBuilder();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mScrollView = new ScrollView(this);
        mConsoleText = new TextView(this);
        mConsoleText.setMovementMethod(new ScrollingMovementMethod());
        mConsoleText.setTextIsSelectable(true);
        mConsoleText.setPadding(16, 16, 16, 16);
        mConsoleText.setTextSize(12);
        mConsoleText.setTypeface(android.graphics.Typeface.MONOSPACE);
        mScrollView.addView(mConsoleText);
        setContentView(mScrollView);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.settings_key_server_console);
        }

        // Load existing log
        List<String> existingLog = ServerManager.getServerLog();
        for (String line : existingLog) {
            mLogBuilder.append(line).append("\n");
        }
        mConsoleText.setText(mLogBuilder.toString());
        scrollToBottom();

        // Register for updates
        ServerManager.addOutputListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ServerManager.removeOutputListener(this);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onServerOutput(String line) {
        mHandler.post(() -> {
            mLogBuilder.append(line).append("\n");
            mConsoleText.setText(mLogBuilder.toString());
            scrollToBottom();
        });
    }

    private void scrollToBottom() {
        mScrollView.post(() -> mScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
