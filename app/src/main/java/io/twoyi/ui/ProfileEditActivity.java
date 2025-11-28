/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;

import io.twoyi.R;
import io.twoyi.utils.Profile;
import io.twoyi.utils.ProfileManager;

/**
 * Activity for editing a profile's settings
 */
public class ProfileEditActivity extends AppCompatActivity {

    public static final String EXTRA_PROFILE_ID = "profile_id";
    private static final int REQUEST_SELECT_ROOTFS = 1001;

    private ProfileManager profileManager;
    private Profile profile;

    private EditText etName;
    private EditText etControlPort;
    private EditText etAdbPort;
    private RadioGroup rgMode;
    private RadioButton rbLegacy;
    private RadioButton rbServer;
    private CheckBox cbVerboseDebug;
    private CheckBox cb3rdPartyRom;
    private TextView tvRootfsPath;
    private TextView tvRootfsStatus;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_edit);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        profileManager = ProfileManager.getInstance(this);

        String profileId = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        if (profileId == null) {
            Toast.makeText(this, "Profile not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        profile = profileManager.getProfileById(profileId);
        if (profile == null) {
            Toast.makeText(this, "Profile not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        loadProfileData();
    }

    private void initViews() {
        etName = findViewById(R.id.etProfileName);
        etControlPort = findViewById(R.id.etControlPort);
        etAdbPort = findViewById(R.id.etAdbPort);
        rgMode = findViewById(R.id.rgMode);
        rbLegacy = findViewById(R.id.rbLegacy);
        rbServer = findViewById(R.id.rbServer);
        cbVerboseDebug = findViewById(R.id.cbVerboseDebug);
        cb3rdPartyRom = findViewById(R.id.cb3rdPartyRom);
        tvRootfsPath = findViewById(R.id.tvRootfsPath);
        tvRootfsStatus = findViewById(R.id.tvRootfsStatus);

        findViewById(R.id.btnSelectRootfs).setOnClickListener(v -> selectRootfsPath());
        findViewById(R.id.btnClearRootfs).setOnClickListener(v -> clearRootfsPath());
    }

    private void loadProfileData() {
        etName.setText(profile.getName());
        etControlPort.setText(profile.getControlPort());
        etAdbPort.setText(profile.getAdbPort());
        
        if (profile.isLegacyMode()) {
            rbLegacy.setChecked(true);
        } else {
            rbServer.setChecked(true);
        }

        cbVerboseDebug.setChecked(profile.isVerboseDebug());
        cb3rdPartyRom.setChecked(profile.isUse3rdPartyRom());

        updateRootfsDisplay();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.edit_profile_title, profile.getName()));
        }
    }

    private void updateRootfsDisplay() {
        String rootfsPath = profile.getRootfsPath();
        if (rootfsPath == null || rootfsPath.isEmpty()) {
            File defaultPath = profileManager.getRootfsDir(profile);
            tvRootfsPath.setText(getString(R.string.profile_rootfs_default, defaultPath.getAbsolutePath()));
        } else {
            tvRootfsPath.setText(rootfsPath);
        }

        // Check if rootfs is initialized
        boolean isInitialized = profileManager.isProfileRootfsInitialized(profile);
        if (isInitialized) {
            tvRootfsStatus.setText(R.string.profile_rootfs_initialized);
            tvRootfsStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        } else {
            tvRootfsStatus.setText(R.string.profile_rootfs_not_initialized);
            tvRootfsStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
        }
    }

    private void selectRootfsPath() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_SELECT_ROOTFS);
    }

    private void clearRootfsPath() {
        profile.setRootfsPath("");
        updateRootfsDisplay();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_SELECT_ROOTFS && resultCode == Activity.RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                // Take persistable permission
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                
                // Convert to file path if possible
                DocumentFile docFile = DocumentFile.fromTreeUri(this, treeUri);
                if (docFile != null) {
                    // Store the URI as string since we can't always get a file path
                    profile.setRootfsPath(treeUri.toString());
                    updateRootfsDisplay();
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_profile_edit, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (item.getItemId() == R.id.action_save) {
            saveProfile();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void saveProfile() {
        String name = etName.getText().toString().trim();
        String controlPort = etControlPort.getText().toString().trim();
        String adbPort = etAdbPort.getText().toString().trim();

        // Validate name
        if (name.isEmpty()) {
            etName.setError(getString(R.string.profile_name_empty));
            return;
        }
        if (!profileManager.isNameUnique(name, profile.getId())) {
            etName.setError(getString(R.string.profile_name_exists));
            return;
        }

        // Validate ports
        if (!isValidPort(controlPort)) {
            etControlPort.setError(getString(R.string.invalid_port));
            return;
        }
        if (!isValidPort(adbPort)) {
            etAdbPort.setError(getString(R.string.invalid_port));
            return;
        }

        // Update profile
        profile.setName(name);
        profile.setControlPort(controlPort);
        profile.setAdbPort(adbPort);
        profile.setMode(rbLegacy.isChecked() ? Profile.MODE_LEGACY : Profile.MODE_SERVER);
        profile.setVerboseDebug(cbVerboseDebug.isChecked());
        profile.setUse3rdPartyRom(cb3rdPartyRom.isChecked());

        profileManager.updateProfile(profile);
        
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private boolean isValidPort(String portStr) {
        try {
            int port = Integer.parseInt(portStr);
            return port > 0 && port < 65536;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void onBackPressed() {
        // Prompt to save if changes were made
        saveProfile();
    }
}
