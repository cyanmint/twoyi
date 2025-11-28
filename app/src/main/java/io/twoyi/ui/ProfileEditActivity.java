/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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
    private Profile originalProfile; // For tracking changes

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

        // Store original values for change detection
        try {
            originalProfile = Profile.fromJson(profile.toJson());
        } catch (Exception e) {
            originalProfile = null;
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
        // Show options: internal storage paths or custom path
        String[] options = new String[]{
            getString(R.string.profile_rootfs_internal_default),
            getString(R.string.profile_rootfs_internal_custom),
            getString(R.string.profile_rootfs_external)
        };
        
        new AlertDialog.Builder(this)
            .setTitle(R.string.profile_select_rootfs)
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: // Default internal path (profile-specific)
                        profile.setRootfsPath("");
                        updateRootfsDisplay();
                        break;
                    case 1: // Custom internal path
                        showInternalPathDialog();
                        break;
                    case 2: // External storage (document picker)
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        startActivityForResult(intent, REQUEST_SELECT_ROOTFS);
                        break;
                }
            })
            .show();
    }

    private void showInternalPathDialog() {
        // Get internal storage base path
        File dataDir = getDataDir();
        String basePath = dataDir.getAbsolutePath();
        
        // Create EditText for custom path input
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.profile_rootfs_path_hint);
        input.setText(basePath + "/rootfs_custom");
        input.setSelectAllOnFocus(true);
        
        new AlertDialog.Builder(this)
            .setTitle(R.string.profile_rootfs_internal_custom)
            .setMessage(getString(R.string.profile_rootfs_internal_hint, basePath))
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String customPath = input.getText().toString().trim();
                if (!customPath.isEmpty()) {
                    // Validate path is within app's data directory using canonical paths
                    try {
                        File baseDirFile = new File(basePath);
                        File customPathFile = new File(customPath);
                        String baseCanonical = baseDirFile.getCanonicalPath();
                        String customCanonical = customPathFile.getCanonicalPath();
                        if (customCanonical.startsWith(baseCanonical)) {
                            profile.setRootfsPath(customPath);
                            updateRootfsDisplay();
                        } else {
                            Toast.makeText(this, R.string.profile_rootfs_invalid_path, Toast.LENGTH_LONG).show();
                        }
                    } catch (java.io.IOException e) {
                        Toast.makeText(this, R.string.profile_rootfs_invalid_path, Toast.LENGTH_LONG).show();
                    }
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void clearRootfsPath() {
        profile.setRootfsPath("");
        updateRootfsDisplay();
    }

    /**
     * Try to convert a content URI to a file path.
     * Returns null if conversion is not possible.
     */
    private String getFilePathFromUri(Uri uri) {
        // For file:// URIs, return the path directly
        if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        }
        
        // For content:// URIs, we can't reliably get a file path
        // The path will be stored as-is and ProfileManager will handle it appropriately
        // by using the default path for content URIs
        return null;
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
                
                // Try to get a file path, otherwise store the URI
                String filePath = getFilePathFromUri(treeUri);
                if (filePath != null) {
                    profile.setRootfsPath(filePath);
                } else {
                    // Store URI - ProfileManager will use default path for URI-based paths
                    // since File operations won't work with content:// URIs
                    profile.setRootfsPath(treeUri.toString());
                    Toast.makeText(this, R.string.profile_uri_path_warning, Toast.LENGTH_LONG).show();
                }
                updateRootfsDisplay();
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

    /**
     * Check if any changes were made to the profile
     */
    private boolean hasChanges() {
        if (originalProfile == null) {
            return true; // Assume changes if we couldn't store original
        }
        
        String name = etName.getText().toString().trim();
        String controlPort = etControlPort.getText().toString().trim();
        String adbPort = etAdbPort.getText().toString().trim();
        String mode = rbLegacy.isChecked() ? Profile.MODE_LEGACY : Profile.MODE_SERVER;
        boolean verboseDebug = cbVerboseDebug.isChecked();
        boolean use3rdPartyRom = cb3rdPartyRom.isChecked();
        
        return !name.equals(originalProfile.getName()) ||
               !controlPort.equals(originalProfile.getControlPort()) ||
               !adbPort.equals(originalProfile.getAdbPort()) ||
               !mode.equals(originalProfile.getMode()) ||
               verboseDebug != originalProfile.isVerboseDebug() ||
               use3rdPartyRom != originalProfile.isUse3rdPartyRom() ||
               !safeEquals(profile.getRootfsPath(), originalProfile.getRootfsPath());
    }

    private boolean safeEquals(String s1, String s2) {
        if (s1 == null && s2 == null) return true;
        if (s1 == null || s2 == null) return false;
        return s1.equals(s2);
    }

    @Override
    public void onBackPressed() {
        if (hasChanges()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.unsaved_changes_title)
                    .setMessage(R.string.unsaved_changes_message)
                    .setPositiveButton(R.string.save_and_exit, (dialog, which) -> saveProfile())
                    .setNegativeButton(R.string.discard_changes, (dialog, which) -> finish())
                    .setNeutralButton(android.R.string.cancel, null)
                    .show();
        } else {
            finish();
        }
    }
}
