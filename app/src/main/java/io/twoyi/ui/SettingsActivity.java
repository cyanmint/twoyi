/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.provider.DocumentsContract;
import android.util.DisplayMetrics;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.util.Pair;

import com.microsoft.appcenter.crashes.Crashes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

import io.twoyi.R;
import io.twoyi.Render2Activity;
import io.twoyi.RemoteRenderActivity;
import io.twoyi.ScrcpyRenderActivity;
import io.twoyi.utils.AppKV;
import io.twoyi.utils.LogEvents;
import io.twoyi.utils.Profile;
import io.twoyi.utils.ProfileManager;
import io.twoyi.utils.RomManager;
import io.twoyi.utils.ServerManager;
import io.twoyi.utils.UIHelper;

/**
 * @author weishu
 * @date 2022/1/2.
 */

public class SettingsActivity extends AppCompatActivity {

    private static final int REQUEST_GET_FILE = 1000;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);
        SettingsFragment settingsFragment = new SettingsFragment();
        getFragmentManager().beginTransaction()
                .replace(R.id.settingsFrameLayout, settingsFragment)
                .commit();

        ActionBar actionBar = getSupportActionBar();

        if (actionBar != null) {
            // Show the "up" button only if this activity is not the root of the task
            actionBar.setDisplayHomeAsUpEnabled(!isTaskRoot());
            actionBar.setBackgroundDrawable(getResources().getDrawable(R.color.colorPrimary));
            actionBar.setTitle(R.string.title_settings);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragment {
        private ProfileManager profileManager;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_settings);
            profileManager = ProfileManager.getInstance(getActivity());
        }

        private Preference findPreference(@StringRes int id) {
            String key = getString(id);
            return findPreference(key);
        }

        @Override
        public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            // Profile management
            Preference manageProfiles = findPreference("manage_profiles");
            if (manageProfiles != null) {
                updateProfileSummary(manageProfiles);
                manageProfiles.setOnPreferenceClickListener(preference -> {
                    UIHelper.startActivity(getContext(), ProfileListActivity.class);
                    return true;
                });
            }

            // Local container settings
            Preference startLocalLegacy = findPreference("start_local_legacy");
            startLocalLegacy.setOnPreferenceClickListener(preference -> {
                startLocalLegacy();
                return true;
            });

            // Server settings - use literal keys that match the XML
            Preference serverAddress = findPreference("server_address");
            Preference adbAddress = findPreference("adb_address");
            Preference startServer = findPreference("start_server");
            Preference connectServer = findPreference("connect_server");
            Preference connectScrcpy = findPreference("connect_scrcpy");
            Preference serverConsole = findPreference("server_console");

            // Debug settings
            android.preference.CheckBoxPreference verboseDebug = (android.preference.CheckBoxPreference) findPreference("verbose_debug");
            if (verboseDebug != null) {
                verboseDebug.setChecked(AppKV.getBooleanConfig(getActivity(), AppKV.VERBOSE_DEBUG, false));
                verboseDebug.setOnPreferenceChangeListener((preference, newValue) -> {
                    AppKV.setBooleanConfig(getActivity(), AppKV.VERBOSE_DEBUG, (Boolean) newValue);
                    return true;
                });
            }

            // Update server address summary with current value (from active profile)
            Profile activeProfile = profileManager.getActiveProfile();
            String currentAddress = activeProfile != null ? 
                    activeProfile.getServerAddress() : AppKV.DEFAULT_SERVER_ADDRESS;
            serverAddress.setSummary(getString(R.string.settings_server_address_summary) + "\nCurrent: " + currentAddress);

            // Update ADB address summary with current value (from active profile)
            String currentAdbAddress = activeProfile != null ? 
                    activeProfile.getAdbAddress() : AppKV.DEFAULT_ADB_ADDRESS;
            adbAddress.setSummary(getString(R.string.settings_adb_address_summary) + "\nCurrent: " + currentAdbAddress);

            serverAddress.setOnPreferenceClickListener(preference -> {
                showServerAddressDialog();
                return true;
            });

            adbAddress.setOnPreferenceClickListener(preference -> {
                showAdbAddressDialog();
                return true;
            });

            startServer.setOnPreferenceClickListener(preference -> {
                startLocalServer();
                return true;
            });

            connectServer.setOnPreferenceClickListener(preference -> {
                connectToServer();
                return true;
            });

            connectScrcpy.setOnPreferenceClickListener(preference -> {
                connectViaScrcpy();
                return true;
            });

            serverConsole.setOnPreferenceClickListener(preference -> {
                UIHelper.startActivity(getContext(), ServerConsoleActivity.class);
                return true;
            });

            Preference importApp = findPreference(R.string.settings_key_import_app);
            Preference export = findPreference(R.string.settings_key_manage_files);

            Preference shutdown = findPreference(R.string.settings_key_shutdown);
            Preference reboot = findPreference(R.string.settings_key_reboot);
            Preference replaceRom = findPreference(R.string.settings_key_replace_rom);
            Preference factoryReset = findPreference(R.string.settings_key_factory_reset);

            Preference donate = findPreference(R.string.settings_key_donate);
            Preference sendLog = findPreference(R.string.settings_key_sendlog);
            Preference about = findPreference(R.string.settings_key_about);

            importApp.setOnPreferenceClickListener(preference -> {
                UIHelper.startActivity(getContext(), SelectAppActivity.class);
                return true;
            });

            export.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setType(DocumentsContract.Document.MIME_TYPE_DIR);
                startActivity(intent);
                return true;
            });

            shutdown.setOnPreferenceClickListener(preference -> {
                Activity activity = getActivity();
                ServerManager.stopServer();
                activity.finishAffinity();
                RomManager.shutdown(activity);
                return true;
            });

            reboot.setOnPreferenceClickListener(preference -> {
                Activity activity = getActivity();
                ServerManager.stopServer();
                activity.finishAndRemoveTask();
                RomManager.reboot(activity);
                return true;
            });

            replaceRom.setOnPreferenceClickListener(preference -> {

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);

                // you can only select one rootfs
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                intent.setType("*/*"); // apk file
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(intent, REQUEST_GET_FILE);
                } catch (Throwable ignored) {
                    Toast.makeText(getContext(), "Error", Toast.LENGTH_SHORT).show();
                }
                return true;
            });

            factoryReset.setOnPreferenceClickListener(preference -> {
                UIHelper.getDialogBuilder(getActivity())
                        .setTitle(android.R.string.dialog_alert_title)
                        .setMessage(R.string.factory_reset_confirm_message)
                        .setPositiveButton(R.string.i_confirm_it, (dialog, which) -> {
                            AppKV.setBooleanConfig(getActivity(), AppKV.SHOULD_USE_THIRD_PARTY_ROM, false);
                            AppKV.setBooleanConfig(getActivity(), AppKV.FORCE_ROM_BE_RE_INSTALL, true);
                            dialog.dismiss();

                            RomManager.reboot(getActivity());
                        })
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                        .show();
                return true;
            });

            donate.setOnPreferenceClickListener(preference -> {
                Context context = getContext();
                if (context instanceof Activity) {
                    UIHelper.showDonateDialog((Activity) context);
                    return true;
                }
                return false;
            });

            sendLog.setOnPreferenceClickListener(preference -> {
                Context context = getActivity();
                byte[] bugreport = LogEvents.getBugreport(context);
                File tmpLog = new File(context.getCacheDir(), "bugreport.zip");
                try {
                    Files.write(tmpLog.toPath(), bugreport);
                } catch (IOException e) {
                    Crashes.trackError(e);
                }
                Uri uri = FileProvider.getUriForFile(context, "io.twoyi.fileprovider", tmpLog);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.setDataAndType(uri, "application/zip");
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                context.startActivity(Intent.createChooser(shareIntent, getString(R.string.settings_key_sendlog)));

                return true;
            });

            about.setOnPreferenceClickListener(preference -> {
                UIHelper.startActivity(getContext(), AboutActivity.class);
                return true;
            });
        }

        private void updateProfileSummary(Preference pref) {
            Profile activeProfile = profileManager.getActiveProfile();
            if (activeProfile != null) {
                pref.setSummary(getString(R.string.active_profile, activeProfile.getName()));
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            // Update profile summary when returning from profile management
            Preference manageProfiles = findPreference("manage_profiles");
            if (manageProfiles != null) {
                updateProfileSummary(manageProfiles);
            }
            
            // Update addresses based on active profile
            Profile activeProfile = profileManager.getActiveProfile();
            if (activeProfile != null) {
                Preference serverAddress = findPreference("server_address");
                if (serverAddress != null) {
                    serverAddress.setSummary(getString(R.string.settings_server_address_summary) + 
                            "\nCurrent: " + activeProfile.getServerAddress());
                }
                
                Preference adbAddress = findPreference("adb_address");
                if (adbAddress != null) {
                    adbAddress.setSummary(getString(R.string.settings_adb_address_summary) + 
                            "\nCurrent: " + activeProfile.getAdbAddress());
                }
            }
        }

        private void showServerAddressDialog() {
            Activity activity = getActivity();
            Profile activeProfile = profileManager.getActiveProfile();
            String currentAddress = activeProfile != null ? 
                    activeProfile.getServerAddress() : AppKV.DEFAULT_SERVER_ADDRESS;

            EditText input = new EditText(activity);
            input.setText(currentAddress);
            input.setHint(R.string.server_address_hint);

            new AlertDialog.Builder(activity)
                    .setTitle(R.string.server_address_dialog_title)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        String newAddress = input.getText().toString().trim();
                        if (!newAddress.isEmpty() && activeProfile != null) {
                            // Extract port from address
                            String[] parts = newAddress.split(":");
                            if (parts.length == 2) {
                                activeProfile.setControlPort(parts[1]);
                                profileManager.updateProfile(activeProfile);
                            }
                            Preference serverAddressPref = findPreference("server_address");
                            serverAddressPref.setSummary(getString(R.string.settings_server_address_summary) + "\nCurrent: " + newAddress);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void startLocalServer() {
            Activity activity = getActivity();
            Profile activeProfile = profileManager.getActiveProfile();
            String address = activeProfile != null ? 
                    activeProfile.getServerAddress() : AppKV.DEFAULT_SERVER_ADDRESS;

            ProgressDialog dialog = UIHelper.getProgressDialog(activity);
            dialog.setMessage(getString(R.string.server_connecting));
            dialog.show();

            // Mark active profile as used
            if (activeProfile != null) {
                activeProfile.updateLastUsed();
                profileManager.updateProfile(activeProfile);
            }

            new Thread(() -> {
                try {
                    // Check if rootfs exists, extract if needed
                    boolean romExist = RomManager.romExist(activity);
                    if (!romExist) {
                        activity.runOnUiThread(() -> dialog.setMessage(getString(R.string.extracting_tips)));
                        
                        boolean factoryRomUpdated = RomManager.needsUpgrade(activity);
                        boolean forceInstall = AppKV.getBooleanConfig(activity, AppKV.FORCE_ROM_BE_RE_INSTALL, false);
                        boolean use3rdRom = activeProfile != null ? 
                                activeProfile.isUse3rdPartyRom() : 
                                AppKV.getBooleanConfig(activity, AppKV.SHOULD_USE_THIRD_PARTY_ROM, false);
                        
                        RomManager.extractRootfs(activity.getApplicationContext(), romExist, factoryRomUpdated, forceInstall, use3rdRom);
                        RomManager.initRootfs(activity.getApplicationContext());
                        
                        // Check if extraction succeeded
                        if (!RomManager.romExist(activity)) {
                            throw new IOException("Failed to extract rootfs - ROM file may be missing from assets");
                        }
                        
                        activity.runOnUiThread(() -> dialog.setMessage(getString(R.string.server_connecting)));
                    }

                    // Get screen dimensions
                    DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
                    int width = metrics.widthPixels;
                    int height = metrics.heightPixels;

                    // Start the server
                    ServerManager.startServer(activity, address, width, height);

                    // Wait for server to be ready (with timeout)
                    int maxAttempts = 10;
                    boolean serverReady = false;
                    for (int i = 0; i < maxAttempts && !serverReady; i++) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Server startup interrupted");
                        }
                        serverReady = ServerManager.testConnection(address);
                    }

                    if (!serverReady) {
                        ServerManager.stopServer();
                        throw new IOException("Server did not start within timeout");
                    }
                    activity.runOnUiThread(() -> {
                        dialog.dismiss();
                        Toast.makeText(activity, R.string.server_started, Toast.LENGTH_SHORT).show();

                        // Launch the remote renderer activity
                        Intent intent = new Intent(activity, RemoteRenderActivity.class);
                        intent.putExtra("server_address", address);
                        startActivity(intent);
                    });
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        dialog.dismiss();
                        Toast.makeText(activity, getString(R.string.server_start_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    });
                }
            }, "start-local-server").start();
        }

        private void startLocalLegacy() {
            Activity activity = getActivity();
            Profile activeProfile = profileManager.getActiveProfile();
            
            // Mark active profile as used
            if (activeProfile != null) {
                activeProfile.updateLastUsed();
                profileManager.updateProfile(activeProfile);
            }
            
            // Check if rootfs exists
            boolean romExist = RomManager.romExist(activity);
            
            if (!romExist) {
                // Need to extract rootfs first
                ProgressDialog dialog = UIHelper.getProgressDialog(activity);
                dialog.setMessage(getString(R.string.extracting_tips));
                dialog.show();
                
                new Thread(() -> {
                    boolean factoryRomUpdated = RomManager.needsUpgrade(activity);
                    boolean forceInstall = AppKV.getBooleanConfig(activity, AppKV.FORCE_ROM_BE_RE_INSTALL, false);
                    boolean use3rdRom = activeProfile != null ?
                            activeProfile.isUse3rdPartyRom() :
                            AppKV.getBooleanConfig(activity, AppKV.SHOULD_USE_THIRD_PARTY_ROM, false);
                    
                    RomManager.extractRootfs(activity.getApplicationContext(), false, factoryRomUpdated, forceInstall, use3rdRom);
                    RomManager.initRootfs(activity.getApplicationContext());
                    
                    activity.runOnUiThread(() -> {
                        dialog.dismiss();
                        
                        // Check if extraction succeeded
                        if (RomManager.romExist(activity)) {
                            // Start Render2Activity
                            Intent intent = new Intent(activity, Render2Activity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(intent);
                            activity.finish();
                        } else {
                            Toast.makeText(activity, getString(R.string.server_start_failed, "Failed to extract rootfs"), Toast.LENGTH_LONG).show();
                        }
                    });
                }, "extract-rootfs-legacy").start();
            } else {
                // Rootfs already exists, start Render2Activity directly
                Intent intent = new Intent(activity, Render2Activity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                activity.finish();
            }
        }

        private void connectToServer() {
            Activity activity = getActivity();
            Profile activeProfile = profileManager.getActiveProfile();
            String address = activeProfile != null ?
                    activeProfile.getServerAddress() : AppKV.DEFAULT_SERVER_ADDRESS;

            ProgressDialog dialog = UIHelper.getProgressDialog(activity);
            dialog.setMessage(getString(R.string.server_connecting));
            dialog.show();

            new Thread(() -> {
                boolean connected = ServerManager.testConnection(address);

                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    if (connected) {
                        Toast.makeText(activity, R.string.server_connected, Toast.LENGTH_SHORT).show();

                        // Launch the remote renderer activity
                        Intent intent = new Intent(activity, RemoteRenderActivity.class);
                        intent.putExtra("server_address", address);
                        startActivity(intent);
                    } else {
                        Toast.makeText(activity, getString(R.string.server_connection_failed, "Connection refused"), Toast.LENGTH_LONG).show();
                    }
                });
            }, "test-connection").start();
        }

        private void showAdbAddressDialog() {
            Activity activity = getActivity();
            Profile activeProfile = profileManager.getActiveProfile();
            String currentAddress = activeProfile != null ?
                    activeProfile.getAdbAddress() : AppKV.DEFAULT_ADB_ADDRESS;

            EditText input = new EditText(activity);
            input.setText(currentAddress);
            input.setHint(R.string.adb_address_hint);

            new AlertDialog.Builder(activity)
                    .setTitle(R.string.adb_address_dialog_title)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        String newAddress = input.getText().toString().trim();
                        if (!newAddress.isEmpty() && activeProfile != null) {
                            // Validate address format (host:port)
                            if (!isValidAddressFormat(newAddress)) {
                                Toast.makeText(activity, R.string.invalid_address_format, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            // Extract port from address
                            String[] parts = newAddress.split(":");
                            if (parts.length == 2) {
                                activeProfile.setAdbPort(parts[1]);
                                profileManager.updateProfile(activeProfile);
                            }
                            Preference adbAddressPref = findPreference("adb_address");
                            adbAddressPref.setSummary(getString(R.string.settings_adb_address_summary) + "\nCurrent: " + newAddress);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private boolean isValidAddressFormat(String address) {
            if (address == null || address.isEmpty()) {
                return false;
            }
            String[] parts = address.split(":");
            if (parts.length != 2) {
                return false;
            }
            try {
                int port = Integer.parseInt(parts[1]);
                return port > 0 && port < 65536;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        private void connectViaScrcpy() {
            Activity activity = getActivity();
            Profile activeProfile = profileManager.getActiveProfile();
            String adbAddress = activeProfile != null ?
                    activeProfile.getAdbAddress() : AppKV.DEFAULT_ADB_ADDRESS;

            // Launch the scrcpy renderer activity
            Intent intent = new Intent(activity, ScrcpyRenderActivity.class);
            intent.putExtra("adb_address", adbAddress);
            startActivity(intent);
        }


        @Override
        public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
            super.onActivityResult(requestCode, resultCode, data);

            if (!(requestCode == REQUEST_GET_FILE && resultCode == Activity.RESULT_OK)) {
                return;
            }

            if (data == null) {
                return;
            }

            Uri uri = data.getData();
            if (uri == null) {
                return;
            }

            Activity activity = getActivity();
            ProgressDialog dialog = UIHelper.getProgressDialog(activity);
            dialog.setCancelable(false);
            dialog.show();

            // start copy 3rd rom
            UIHelper.defer().when(() -> {

                File rootfs3rd = RomManager.get3rdRootfsFile(activity);

                ContentResolver contentResolver = activity.getContentResolver();
                try (InputStream inputStream = contentResolver.openInputStream(uri); OutputStream os = new FileOutputStream(rootfs3rd)) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = inputStream.read(buffer)) > 0) {
                        os.write(buffer, 0, count);
                    }
                }

                RomManager.RomInfo romInfo = RomManager.getRomInfo(rootfs3rd);
                return Pair.create(rootfs3rd, romInfo);
            }).done(result -> {

                File rootfs3rd = result.first;
                RomManager.RomInfo romInfo = result.second;
                UIHelper.dismiss(dialog);

                // copy finished, show dialog confirm
                if (romInfo.isValid()) {

                    String author = romInfo.author;
                    if ("weishu".equalsIgnoreCase(author) || "twoyi".equalsIgnoreCase(author)) {
                        Toast.makeText(activity, R.string.replace_rom_unofficial_tips, Toast.LENGTH_SHORT).show();
                        rootfs3rd.delete();
                        return;

                    }
                    UIHelper.getDialogBuilder(activity)
                            .setTitle(R.string.replace_rom_confirm_title)
                            .setMessage(getString(R.string.replace_rom_confirm_message, author, romInfo.version, romInfo.desc))
                            .setPositiveButton(R.string.i_confirm_it, (dialog1, which) -> {
                                AppKV.setBooleanConfig(activity, AppKV.SHOULD_USE_THIRD_PARTY_ROM, true);
                                AppKV.setBooleanConfig(activity, AppKV.FORCE_ROM_BE_RE_INSTALL, true);

                                dialog1.dismiss();

                                RomManager.reboot(getActivity());
                            })
                            .setNegativeButton(android.R.string.cancel, (dialog12, which) -> dialog12.dismiss())
                            .show();
                } else {
                    Toast.makeText(activity, R.string.replace_rom_invalid, Toast.LENGTH_SHORT).show();
                    rootfs3rd.delete();
                }
            }).fail(result -> activity.runOnUiThread(() -> {
                Toast.makeText(activity, getResources().getString(R.string.install_failed_reason, result.getMessage()), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                activity.finish();
            }));

        }
    }
}
