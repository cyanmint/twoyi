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
import android.text.InputType;
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
import io.twoyi.utils.AppKV;
import io.twoyi.utils.LogEvents;
import io.twoyi.utils.RomManager;
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
            actionBar.setDisplayHomeAsUpEnabled(false);
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

        private void updateServerAddressSummary() {
            Preference serverAddress = findPreference(R.string.settings_key_server_address);
            if (serverAddress != null) {
                String address = AppKV.getStringConfig(getContext(), AppKV.SERVER_ADDRESS, AppKV.DEFAULT_SERVER_ADDRESS);
                serverAddress.setSummary(getString(R.string.settings_server_address_summary, address));
            }
        }

        @Override
        public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            // Server connection preferences
            Preference serverAddress = findPreference(R.string.settings_key_server_address);
            Preference startLocalContainer = findPreference(R.string.settings_key_start_local_container);
            Preference startServer = findPreference(R.string.settings_key_start_server);
            Preference connectServer = findPreference(R.string.settings_key_connect_server);

            // Update server address summary
            updateServerAddressSummary();

            serverAddress.setOnPreferenceClickListener(preference -> {
                showServerAddressDialog(false);
                return true;
            });

            startLocalContainer.setOnPreferenceClickListener(preference -> {
                // Start the local container with original Render2Activity behavior
                Intent intent = new Intent(getContext(), Render2Activity.class);
                intent.putExtra("mode", "local");
                startActivity(intent);
                return true;
            });

            startServer.setOnPreferenceClickListener(preference -> {
                // Show dialog to configure and start server
                showServerAddressDialog(true);
                return true;
            });

            connectServer.setOnPreferenceClickListener(preference -> {
                // Connect to remote server
                String address = AppKV.getStringConfig(getContext(), AppKV.SERVER_ADDRESS, AppKV.DEFAULT_SERVER_ADDRESS);
                Intent intent = new Intent(getContext(), Render2Activity.class);
                intent.putExtra("mode", "remote");
                intent.putExtra("server_address", address);
                startActivity(intent);
                return true;
            });

            // Original settings
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
                activity.finishAffinity();
                RomManager.shutdown(activity);
                return true;
            });

            reboot.setOnPreferenceClickListener(preference -> {
                Activity activity = getActivity();
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

        private void showServerAddressDialog(boolean startServerAfter) {
            Context context = getContext();
            if (context == null) return;

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(R.string.server_address_dialog_title);

            EditText input = new EditText(context);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setHint(R.string.server_address_hint);
            String currentAddress = AppKV.getStringConfig(context, AppKV.SERVER_ADDRESS, AppKV.DEFAULT_SERVER_ADDRESS);
            input.setText(currentAddress);
            builder.setView(input);

            builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String address = input.getText().toString().trim();
                if (isValidAddress(address)) {
                    AppKV.setStringConfig(context, AppKV.SERVER_ADDRESS, address);
                    updateServerAddressSummary();
                    
                    if (startServerAfter) {
                        // Start server mode - this would typically involve starting a local server
                        // For now, we just save the address and show a message
                        Toast.makeText(context, "Server configured at " + address, Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(getContext(), Render2Activity.class);
                        intent.putExtra("mode", "server");
                        intent.putExtra("server_address", address);
                        startActivity(intent);
                    }
                } else {
                    Toast.makeText(context, R.string.invalid_server_address, Toast.LENGTH_SHORT).show();
                }
            });

            builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel());
            builder.show();
        }

        private boolean isValidAddress(String address) {
            if (address == null || address.isEmpty()) return false;
            String[] parts = address.split(":");
            if (parts.length != 2) return false;
            try {
                int port = Integer.parseInt(parts[1]);
                return port > 0 && port < 65536;
            } catch (NumberFormatException e) {
                return false;
            }
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
