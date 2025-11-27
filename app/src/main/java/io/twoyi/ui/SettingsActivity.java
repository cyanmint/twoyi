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
import io.twoyi.utils.AppKV;
import io.twoyi.utils.LogEvents;
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
        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_settings);
        }

        private Preference findPreference(@StringRes int id) {
            String key = getString(id);
            return findPreference(key);
        }

        @Override
        public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            // Server settings - use literal keys that match the XML
            Preference serverAddress = findPreference("server_address");
            Preference startServer = findPreference("start_server");
            Preference connectServer = findPreference("connect_server");
            Preference serverConsole = findPreference("server_console");

            // Update server address summary with current value
            String currentAddress = AppKV.getStringConfig(getActivity(), AppKV.SERVER_ADDRESS, AppKV.DEFAULT_SERVER_ADDRESS);
            serverAddress.setSummary(getString(R.string.settings_server_address_summary) + "\nCurrent: " + currentAddress);

            serverAddress.setOnPreferenceClickListener(preference -> {
                showServerAddressDialog();
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

        private void showServerAddressDialog() {
            Activity activity = getActivity();
            String currentAddress = AppKV.getStringConfig(activity, AppKV.SERVER_ADDRESS, AppKV.DEFAULT_SERVER_ADDRESS);

            EditText input = new EditText(activity);
            input.setText(currentAddress);
            input.setHint(R.string.server_address_hint);

            new AlertDialog.Builder(activity)
                    .setTitle(R.string.server_address_dialog_title)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        String newAddress = input.getText().toString().trim();
                        if (!newAddress.isEmpty()) {
                            AppKV.setStringConfig(activity, AppKV.SERVER_ADDRESS, newAddress);
                            Preference serverAddressPref = findPreference("server_address");
                            serverAddressPref.setSummary(getString(R.string.settings_server_address_summary) + "\nCurrent: " + newAddress);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void startLocalServer() {
            Activity activity = getActivity();
            String address = AppKV.getStringConfig(activity, AppKV.SERVER_ADDRESS, AppKV.DEFAULT_SERVER_ADDRESS);

            ProgressDialog dialog = UIHelper.getProgressDialog(activity);
            dialog.setMessage(getString(R.string.server_connecting));
            dialog.show();

            new Thread(() -> {
                try {
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

        private void connectToServer() {
            Activity activity = getActivity();
            String address = AppKV.getStringConfig(activity, AppKV.SERVER_ADDRESS, AppKV.DEFAULT_SERVER_ADDRESS);

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
