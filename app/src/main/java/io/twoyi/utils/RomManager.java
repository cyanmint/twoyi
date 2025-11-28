/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;

/**
 * @author weishu
 * @date 2021/10/22.
 */

public final class RomManager {

    private static final String TAG = "RomManager";

    // Supported ROM file names in order of preference
    private static final String[] ROOTFS_NAMES = {
        "rootfs.tar.gz",
        "rootfs.tgz",
        "rootfs.tar.xz",
        "rootfs.txz",
        "rootfs.tar"
    };

    private static final String ROM_INFO_FILE = "rom.ini";

    private static final String DEFAULT_INFO = "unknown";

    private static final String LOADER_FILE = "libloader.so";

    private static final String CUSTOM_ROM_FILE_NAME = "rootfs_3rd.tar.gz";

    private RomManager() {
    }

    public static void initRootfs(Context context) {
        File rootfsDir = getRootfsDir(context);
        initRootfs(context, rootfsDir);
    }

    /**
     * Initialize rootfs with a specific directory (for profile support)
     */
    public static void initRootfs(Context context, File rootfsDir) {
        File propFile = getVendorPropFile(rootfsDir);
        String language = Locale.getDefault().getLanguage();
        String country = Locale.getDefault().getCountry();

        Properties properties = new Properties();

        properties.setProperty("persist.sys.language", language);
        properties.setProperty("persist.sys.country", country);

        TimeZone timeZone = TimeZone.getDefault();
        String timeZoneID = timeZone.getID();
        Log.i(TAG, "timezone: " + timeZoneID);
        properties.setProperty("persist.sys.timezone", timeZoneID);

        properties.setProperty("ro.sf.lcd_density", String.valueOf(DisplayMetrics.DENSITY_DEVICE_STABLE));

        try (Writer writer = new FileWriter(propFile)) {
            properties.store(writer, null);
        } catch (IOException ignored) {
        }
    }

    public static void ensureBootFiles(Context context) {
        File rootfsDir = getRootfsDir(context);
        ensureBootFiles(context, rootfsDir);
    }

    /**
     * Ensure boot files exist in a specific rootfs directory (for profile support)
     */
    public static void ensureBootFiles(Context context, File rootfsDir) {
        // <rootdir>/dev/
        File devDir = new File(rootfsDir, "dev");
        ensureDir(new File(devDir, "input"));
        ensureDir(new File(devDir, "socket"));
        ensureDir(new File(devDir, "maps"));

        ensureDir(new File(context.getDataDir(), "socket"));

        createLoaderSymlink(context);

        killOrphanProcess();

        saveLastKmsg(context);
    }

    private static void createLoaderSymlink(Context context) {
        Path loaderSymlink = new File(context.getDataDir(), "loader64").toPath();
        String loaderPath = getLoaderPath(context);
        try {
            Files.deleteIfExists(loaderSymlink);
            Files.createSymbolicLink(loaderSymlink, Paths.get(loaderPath));
        } catch (IOException e) {
            throw new RuntimeException("symlink loader failed.", e);
        }
    }

    private static void killOrphanProcess() {
        Shell shell = ShellUtil.newSh();
        shell.newJob().add("ps -ef | awk '{if($3==1) print $2}' | xargs kill -9").exec();
    }

    private static void saveLastKmsg(Context context) {
        File lastKmsgFile = LogEvents.getLastKmsgFile(context);
        File kmsgFile = LogEvents.getKmsgFile(context);
        try {
            Files.move(kmsgFile.toPath(), lastKmsgFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    public static class RomInfo {
        public String author = DEFAULT_INFO;
        public String version = DEFAULT_INFO;
        public String desc = DEFAULT_INFO;
        public String md5 = "";
        public long code = 0;

        @Override
        public String toString() {
            return "RomInfo{" +
                    "author='" + author + '\'' +
                    ", version='" + version + '\'' +
                    ", md5='" + md5 + '\'' +
                    ", code=" + code +
                    '}';
        }

        public boolean isValid() {
            return this != DEFAULT_ROM_INFO;
        }
    }

    public static final RomInfo DEFAULT_ROM_INFO = new RomInfo();

    public static boolean romExist(Context context) {
        File initFile = new File(getRootfsDir(context), "init");
        return initFile.exists();
    }

    /**
     * Check if ROM exists in a specific rootfs directory
     */
    public static boolean romExist(File rootfsDir) {
        File initFile = new File(rootfsDir, "init");
        return initFile.exists();
    }

    public static boolean needsUpgrade(Context context) {
        RomInfo currentRomInfo = getCurrentRomInfo(context);
        Log.i(TAG, "current rom: " + currentRomInfo);
        if (currentRomInfo.equals(DEFAULT_ROM_INFO)) {
            return true;
        }

        RomInfo romInfoFromAssets = getRomInfoFromAssets(context);
        Log.i(TAG, "asset rom: " + romInfoFromAssets);
        return romInfoFromAssets.code > currentRomInfo.code;
    }

    /**
     * Check if ROM needs upgrade in a specific rootfs directory
     */
    public static boolean needsUpgrade(Context context, File rootfsDir) {
        RomInfo currentRomInfo = getCurrentRomInfo(rootfsDir);
        Log.i(TAG, "current rom in " + rootfsDir + ": " + currentRomInfo);
        if (currentRomInfo.equals(DEFAULT_ROM_INFO)) {
            return true;
        }

        RomInfo romInfoFromAssets = getRomInfoFromAssets(context);
        Log.i(TAG, "asset rom: " + romInfoFromAssets);
        return romInfoFromAssets.code > currentRomInfo.code;
    }

    public static RomInfo getCurrentRomInfo(Context context) {
        File infoFile = new File(getRootfsDir(context), ROM_INFO_FILE);
        try (FileInputStream inputStream = new FileInputStream(infoFile)) {
            return getRomInfo(inputStream);
        } catch (Throwable e) {
            return DEFAULT_ROM_INFO;
        }
    }

    /**
     * Get ROM info from a specific rootfs directory
     */
    public static RomInfo getCurrentRomInfo(File rootfsDir) {
        File infoFile = new File(rootfsDir, ROM_INFO_FILE);
        try (FileInputStream inputStream = new FileInputStream(infoFile)) {
            return getRomInfo(inputStream);
        } catch (Throwable e) {
            return DEFAULT_ROM_INFO;
        }
    }

    public static String getLoaderPath(Context context) {
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        return new File(applicationInfo.nativeLibraryDir, LOADER_FILE).getAbsolutePath();
    }

    public static RomInfo getRomInfo(File rom) {
        try (TarArchiveInputStream tais = createTarInputStream(rom)) {

            TarArchiveEntry entry;
            while ((entry = tais.getNextTarEntry()) != null) {
                // Archive has files directly at root (./rom.ini or rom.ini)
                String name = entry.getName();
                if (name.equals("rom.ini") || name.equals("./rom.ini")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = tais.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    return getRomInfo(new java.io.ByteArrayInputStream(baos.toByteArray()));
                }
            }
        } catch (Throwable e) {
            LogEvents.trackError(e);
        }
        return DEFAULT_ROM_INFO;
    }

    public static RomInfo getRomInfoFromAssets(Context context) {
        AssetManager assets = context.getAssets();
        try (InputStream open = assets.open(ROM_INFO_FILE)) {
            return getRomInfo(open);
        } catch (Throwable ignored) {
        }
        return DEFAULT_ROM_INFO;
    }

    public static void extractRootfs(Context context, boolean romExist, boolean needsUpgrade, boolean forceInstall, boolean use3rdRom) {

        // force remove system dir to avoiding wired issues
        removeSystemPartition(context);
        removeVendorPartition(context);

        if (!romExist) {
            // first init
            extractRootfsInAssets(context);
            return;
        }

        if (forceInstall) {
            if (use3rdRom) {
                // install 3rd rom
                boolean success = extract3rdRootfs(context);
                if (!success) {
                    showRootfsInstallationFailure(context);
                    return;
                }
            } else {
                // factory reset!!
                if (!extractRootfsInAssets(context)) {
                    showRootfsInstallationFailure(context);
                    return;
                }
            }

            // force install finish, reset the state.
            AppKV.setBooleanConfig(context, AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        } else {
            if (use3rdRom) {
                Log.w(TAG, "WTF? 3rd ROM must be force install!");
            }
            if (needsUpgrade) {
                Log.i(TAG, "upgrade factory rom..");
                if (!extractRootfsInAssets(context)) {
                    showRootfsInstallationFailure(context);
                }
            }
        }
    }

    /**
     * Extract rootfs to a specific directory (for profile support)
     */
    public static void extractRootfs(Context context, File rootfsDir, boolean romExist, boolean needsUpgrade, boolean forceInstall, boolean use3rdRom) {

        // force remove system dir to avoiding wired issues
        removeSystemPartition(rootfsDir);
        removeVendorPartition(rootfsDir);

        if (!romExist) {
            // first init
            extractRootfsInAssets(context, rootfsDir);
            return;
        }

        if (forceInstall) {
            if (use3rdRom) {
                // install 3rd rom
                boolean success = extract3rdRootfs(context, rootfsDir);
                if (!success) {
                    showRootfsInstallationFailure(context);
                    return;
                }
            } else {
                // factory reset!!
                if (!extractRootfsInAssets(context, rootfsDir)) {
                    showRootfsInstallationFailure(context);
                    return;
                }
            }

            // force install finish, reset the state.
            AppKV.setBooleanConfig(context, AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        } else {
            if (use3rdRom) {
                Log.w(TAG, "WTF? 3rd ROM must be force install!");
            }
            if (needsUpgrade) {
                Log.i(TAG, "upgrade factory rom..");
                if (!extractRootfsInAssets(context, rootfsDir)) {
                    showRootfsInstallationFailure(context);
                }
            }
        }
    }

    private static void showRootfsInstallationFailure(Context context) {
        // TODO
    }

    public static void reboot(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        context.getApplicationContext().startActivity(intent);

        shutdown(context);
    }

    public static void shutdown(Context context) {
        System.exit(0);
        Process.killProcess(Process.myPid());
    }

    public static boolean extract3rdRootfs(Context context) {
        File rootfs3rd = get3rdRootfsFile(context);
        if (!rootfs3rd.exists()) {
            return false;
        }
        int err = extractRootfs(context, rootfs3rd);
        return err == 0;
    }

    /**
     * Extract 3rd party rootfs to a specific directory
     */
    public static boolean extract3rdRootfs(Context context, File rootfsDir) {
        File rootfs3rd = get3rdRootfsFile(context);
        if (!rootfs3rd.exists()) {
            return false;
        }
        int err = extractRootfs(rootfsDir, rootfs3rd);
        return err == 0;
    }

    /**
     * Creates a TarArchiveInputStream based on the file extension.
     * Supports .tar.gz, .tgz, .tar.xz, .txz, and plain .tar
     */
    private static TarArchiveInputStream createTarInputStream(File archiveFile) throws IOException {
        String name = archiveFile.getName().toLowerCase();
        FileInputStream fis = new FileInputStream(archiveFile);
        BufferedInputStream bis = new BufferedInputStream(fis);
        
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            return new TarArchiveInputStream(new GzipCompressorInputStream(bis));
        } else if (name.endsWith(".tar.xz") || name.endsWith(".txz")) {
            return new TarArchiveInputStream(new XZCompressorInputStream(bis));
        } else {
            // Plain .tar or unknown - try as plain tar
            return new TarArchiveInputStream(bis);
        }
    }

    public static int extractRootfs(Context context, File rootfsArchive) {
        File rootfsDir = getRootfsDir(context);
        return extractRootfs(rootfsDir, rootfsArchive);
    }

    /**
     * Extract rootfs archive to a specific directory
     */
    public static int extractRootfs(File rootfsDir, File rootfsArchive) {
        
        // Ensure rootfs directory exists
        if (!rootfsDir.exists() && !rootfsDir.mkdirs()) {
            Log.e(TAG, "Failed to create rootfs directory: " + rootfsDir);
            return -1;
        }
        
        try (TarArchiveInputStream tais = createTarInputStream(rootfsArchive)) {

            TarArchiveEntry entry;
            while ((entry = tais.getNextTarEntry()) != null) {
                // Get the entry name and strip leading "./" if present
                String name = entry.getName();
                if (name.startsWith("./")) {
                    name = name.substring(2);
                }
                if (name.isEmpty()) {
                    continue;
                }
                
                File outputFile = new File(rootfsDir, name);
                
                if (entry.isDirectory()) {
                    if (!outputFile.exists() && !outputFile.mkdirs()) {
                        Log.w(TAG, "Failed to create directory: " + outputFile);
                    }
                } else if (entry.isSymbolicLink()) {
                    // Handle symbolic links
                    String linkTarget = entry.getLinkName();
                    try {
                        // Ensure parent directory exists
                        File parent = outputFile.getParentFile();
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs();
                        }
                        Files.deleteIfExists(outputFile.toPath());
                        Files.createSymbolicLink(outputFile.toPath(), Paths.get(linkTarget));
                    } catch (IOException e) {
                        Log.w(TAG, "Failed to create symlink: " + outputFile + " -> " + linkTarget, e);
                    }
                } else if (entry.isLink()) {
                    // Handle hard links
                    String linkTarget = entry.getLinkName();
                    try {
                        File parent = outputFile.getParentFile();
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs();
                        }
                        File targetFile = new File(rootfsDir, linkTarget);
                        Files.deleteIfExists(outputFile.toPath());
                        Files.createLink(outputFile.toPath(), targetFile.toPath());
                    } catch (IOException e) {
                        Log.w(TAG, "Failed to create hardlink: " + outputFile + " -> " + linkTarget, e);
                    }
                } else {
                    // Regular file
                    File parent = outputFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        Log.w(TAG, "Failed to create parent directory: " + parent);
                    }
                    
                    try (FileOutputStream fos = new FileOutputStream(outputFile);
                         BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = tais.read(buffer)) > 0) {
                            bos.write(buffer, 0, len);
                        }
                    }
                    
                    // Set file permissions if available
                    int mode = entry.getMode();
                    if ((mode & 0111) != 0) {
                        outputFile.setExecutable(true, false);
                    }
                }
            }
            return 0; // Success
        } catch (Throwable e) {
            Log.e(TAG, "Failed to extract rootfs", e);
            LogEvents.trackError(e);
            return -1; // Error
        }
    }

    /**
     * Find the ROM file in assets, trying all supported formats.
     * Returns the filename if found, null otherwise.
     */
    private static String findRomInAssets(AssetManager assets) {
        try {
            String[] assetList = assets.list("");
            if (assetList != null) {
                Log.d(TAG, "Assets available: " + String.join(", ", assetList));
                for (String rootfsName : ROOTFS_NAMES) {
                    for (String asset : assetList) {
                        if (rootfsName.equals(asset)) {
                            Log.i(TAG, "Found ROM file in assets: " + rootfsName);
                            return rootfsName;
                        }
                    }
                }
                Log.w(TAG, "No matching ROM file found. Looking for: " + String.join(", ", ROOTFS_NAMES));
            } else {
                Log.w(TAG, "Asset list is null");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to list assets", e);
        }
        return null;
    }

    public static boolean extractRootfsInAssets(Context context) {
        File rootfsDir = getRootfsDir(context);
        return extractRootfsInAssets(context, rootfsDir);
    }

    /**
     * Extract rootfs from assets to a specific directory (for profile support)
     */
    public static boolean extractRootfsInAssets(Context context, File rootfsDir) {
        Log.i(TAG, "extractRootfsInAssets called for directory: " + rootfsDir.getAbsolutePath());
        
        // Ensure rootfs directory exists
        Log.d(TAG, "Rootfs directory: " + rootfsDir.getAbsolutePath());
        
        if (!rootfsDir.exists() && !rootfsDir.mkdirs()) {
            Log.e(TAG, "Failed to create rootfs directory");
            return false;
        }

        // Find ROM file in assets (try all supported formats)
        AssetManager assets = context.getAssets();
        String romFileName = findRomInAssets(assets);

        if (romFileName == null) {
            Log.w(TAG, "No ROM file found in assets. Tried: " + String.join(", ", ROOTFS_NAMES));
            return false;
        }

        Log.i(TAG, "Extracting ROM from asset: " + romFileName);
        
        // read assets
        long t1 = SystemClock.elapsedRealtime();
        File rootfsArchive = context.getFileStreamPath(romFileName);
        Log.d(TAG, "Temporary archive path: " + rootfsArchive.getAbsolutePath());
        
        try (InputStream inputStream = new BufferedInputStream(assets.open(romFileName));
             OutputStream os = new BufferedOutputStream(new FileOutputStream(rootfsArchive))) {
            byte[] buffer = new byte[10240];
            int count;
            long totalBytes = 0;
            while ((count = inputStream.read(buffer)) > 0) {
                os.write(buffer, 0, count);
                totalBytes += count;
            }
            Log.d(TAG, "Copied " + totalBytes + " bytes from asset to " + rootfsArchive.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy ROM from assets: " + e.getMessage(), e);
            return false;
        }
        long t2 = SystemClock.elapsedRealtime();

        Log.i(TAG, "Starting rootfs extraction from " + rootfsArchive.getAbsolutePath());
        int ret = extractRootfs(rootfsDir, rootfsArchive);

        long t3 = SystemClock.elapsedRealtime();

        Log.i(TAG, "extract rootfs complete. Read assets: " + (t2 - t1) + "ms, untar: " + (t3 - t2) + "ms, ret: " + ret);
        
        // Verify extraction
        File initFile = new File(rootfsDir, "init");
        if (initFile.exists()) {
            Log.i(TAG, "Extraction verified: init file exists at " + initFile.getAbsolutePath());
        } else {
            Log.e(TAG, "Extraction failed: init file NOT found at " + initFile.getAbsolutePath());
        }

        return ret == 0;
    }

    public static File getRootfsDir(Context context) {
        return new File(context.getDataDir(), "rootfs");
    }

    public static File getRomSdcardDir(Context context) {
        return new File(getRootfsDir(context), "sdcard");
    }

    /**
     * Get ROM sdcard directory for a specific rootfs directory
     */
    public static File getRomSdcardDir(File rootfsDir) {
        return new File(rootfsDir, "sdcard");
    }

    public static File getVendorDir(Context context) {
        return new File(getRootfsDir(context), "vendor");
    }

    /**
     * Get vendor directory for a specific rootfs directory
     */
    public static File getVendorDir(File rootfsDir) {
        return new File(rootfsDir, "vendor");
    }

    public static File getVendorPropFile(Context context) {
        return new File(getVendorDir(context), "default.prop");
    }

    /**
     * Get vendor prop file for a specific rootfs directory
     */
    public static File getVendorPropFile(File rootfsDir) {
        return new File(getVendorDir(rootfsDir), "default.prop");
    }

    public static File get3rdRootfsFile(Context context) {
        return context.getFileStreamPath(CUSTOM_ROM_FILE_NAME);
    }

    public static boolean isAndroid12() {
        return Build.VERSION.PREVIEW_SDK_INT + Build.VERSION.SDK_INT == Build.VERSION_CODES.S;
    }

    private static void removePartition(Context context, String partition) {
        File rootfsDir = getRootfsDir(context);
        removePartition(rootfsDir, partition);
    }

    /**
     * Remove partition from a specific rootfs directory
     */
    private static void removePartition(File rootfsDir, String partition) {
        File systemDir = new File(rootfsDir, partition);
        IOUtils.deleteDirectory(systemDir);
    }

    private static void removeSystemPartition(Context context) {
        removePartition(context, "system");
    }

    /**
     * Remove system partition from a specific rootfs directory
     */
    private static void removeSystemPartition(File rootfsDir) {
        removePartition(rootfsDir, "system");
    }

    private static void removeVendorPartition(Context context) {
        removePartition(context, "vendor");
    }

    /**
     * Remove vendor partition from a specific rootfs directory
     */
    private static void removeVendorPartition(File rootfsDir) {
        removePartition(rootfsDir, "vendor");
    }

    private static RomInfo getRomInfo(InputStream in) {
        Properties prop = new Properties();
        try {
            prop.load(in);

            RomInfo info = new RomInfo();
            info.author = prop.getProperty("author");
            info.code = Long.parseLong(prop.getProperty("code"));
            info.version = prop.getProperty("version");
            info.desc = prop.getProperty("desc", DEFAULT_INFO);
            info.md5 = prop.getProperty("md5");
            return info;
        } catch (Throwable e) {
            Log.e(TAG, "read rom info err", e);
            return DEFAULT_ROM_INFO;
        }
    }

    private static void ensureDir(File file) {
        if (file.exists()) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        file.mkdirs();
    }
}
