/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
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

    private static final String ROM_INFO_FILE = "rom.ini";

    private static final String DEFAULT_INFO = "unknown";

    private static final String LOADER_FILE = "libloader.so";

    private static final String CUSTOM_ROM_FILE_NAME = "rootfs_3rd.tar.gz";
    
    // File permission constants for tar archive extraction
    // Using octal notation for POSIX file permissions (standard Java octal prefix is 0)
    // These constants match the standard Unix permission bits:
    private static final int OWNER_EXECUTE = 0100;  // Octal 0100 = decimal 64  = S_IXUSR (owner execute)
    private static final int OWNER_READ = 0400;     // Octal 0400 = decimal 256 = S_IRUSR (owner read)
    private static final int OWNER_WRITE = 0200;    // Octal 0200 = decimal 128 = S_IWUSR (owner write)

    private RomManager() {
    }

    public static void initRootfs(Context context) {
        File propFile = getVendorPropFile(context);
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

        // <rootdir>/dev/
        File devDir = new File(getRootfsDir(context), "dev");
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

    public static RomInfo getCurrentRomInfo(Context context) {
        File infoFile = new File(getRootfsDir(context), ROM_INFO_FILE);
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
        File tempDir = null;
        try {
            // Create temp directory to extract rom.ini
            tempDir = Files.createTempDirectory("rominfo").toFile();
            
            // Extract only rom.ini using system tar
            Shell shell = ShellUtil.newSh();
            Shell.Result result = shell.newJob()
                .add(String.format("tar -xzf '%s' -C '%s' rom.ini 2>/dev/null || true",
                    rom.getAbsolutePath(),
                    tempDir.getAbsolutePath()))
                .exec();
            
            // Look for rom.ini in temp directory
            File romIni = new File(tempDir, "rom.ini");
            
            if (romIni.exists()) {
                try (FileInputStream fis = new FileInputStream(romIni)) {
                    return getRomInfo(fis);
                }
            }
        } catch (Throwable e) {
            LogEvents.trackError(e);
        } finally {
            // Clean up temp directory
            if (tempDir != null && tempDir.exists()) {
                IOUtils.deleteDirectory(tempDir);
            }
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
            // No bundled rootfs - user must import via tarball
            Log.i(TAG, "No rootfs found. User must import rootfs tarball.");
            return;
        }

        if (forceInstall) {
            if (use3rdRom) {
                // install 3rd rom from imported tarball
                boolean success = extract3rdRootfs(context);
                if (!success) {
                    showRootfsInstallationFailure(context);
                    return;
                }
            } else {
                // factory reset - no bundled rootfs available
                Log.w(TAG, "Factory reset requested but no bundled rootfs available");
                showRootfsInstallationFailure(context);
                return;
            }

            // force install finish, reset the state.
            AppKV.setBooleanConfig(context, AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        }
        // No automatic upgrades since there's no bundled rootfs
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
        try {
            extractTarballToDataDir(context, rootfs3rd);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract 3rd rootfs", e);
            return false;
        }
    }

    private static void extractTarballToDataDir(Context context, File tarballFile) throws IOException {
        File outputDir = context.getDataDir();
        
        // Use system tar command for extraction (preserves symlinks and permissions)
        // cd into output directory and extract archive there
        Shell shell = ShellUtil.newSh();
        Shell.Result result = shell.newJob()
            .add(String.format("cd '%s' && tar xzf '%s'", 
                outputDir.getAbsolutePath(),
                tarballFile.getAbsolutePath()))
            .exec();
        
        if (!result.isSuccess()) {
            String errorMsg = result.getErr().isEmpty() ? "Unknown error" : String.join("\n", result.getErr());
            throw new IOException("tar extraction failed: " + errorMsg);
        }
    }

    public static File getRootfsDir(Context context) {
        return new File(context.getDataDir(), "rootfs");
    }

    public static File getRomSdcardDir(Context context) {
        return new File(getRootfsDir(context), "sdcard");
    }

    public static File getVendorDir(Context context) {
        return new File(getRootfsDir(context), "vendor");
    }

    public static File getVendorPropFile(Context context) {
        return new File(getVendorDir(context), "default.prop");
    }

    public static File get3rdRootfsFile(Context context) {
        return context.getFileStreamPath(CUSTOM_ROM_FILE_NAME);
    }

    public static boolean isAndroid12() {
        return Build.VERSION.PREVIEW_SDK_INT + Build.VERSION.SDK_INT == Build.VERSION_CODES.S;
    }

    private static void removePartition(Context context, String partition) {
        File rootfsDir = getRootfsDir(context);
        File systemDir = new File(rootfsDir, partition);

        IOUtils.deleteDirectory(systemDir);
    }

    private static void removeSystemPartition(Context context) {
        removePartition(context, "system");
    }

    private static void removeVendorPartition(Context context) {
        removePartition(context, "vendor");
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

    public static void exportRootfsToTarball(Context context, Uri outputUri) throws IOException {
        File rootfsDir = getRootfsDir(context);
        
        // Create a temporary tarball using system tar (preserves symlinks and permissions)
        File tempTar = new File(context.getCacheDir(), "rootfs_export_temp.tar.gz");
        try {
            // Use system tar to create archive with all files including symlinks
            // tar czf <archive> -C <dir> . creates archive from directory contents without dir name
            Shell shell = ShellUtil.newSh();
            Shell.Result result = shell.newJob()
                .add(String.format("tar czf '%s' -C '%s' .", 
                    tempTar.getAbsolutePath(),
                    rootfsDir.getAbsolutePath()))
                .exec();
            
            if (!result.isSuccess()) {
                String errorMsg = result.getErr().isEmpty() ? "Unknown error" : String.join("\n", result.getErr());
                throw new IOException("tar creation failed: " + errorMsg);
            }
            
            // Copy the temp tarball to the output URI
            ContentResolver contentResolver = context.getContentResolver();
            try (FileInputStream fis = new FileInputStream(tempTar);
                 OutputStream outputStream = contentResolver.openOutputStream(outputUri)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = fis.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, count);
                }
            }
        } finally {
            // Clean up temp file
            if (tempTar.exists()) {
                tempTar.delete();
            }
        }
    }

    public static void importRootfsFromTarball(Context context, Uri inputUri) throws IOException {
        File rootfsDir = getRootfsDir(context);
        
        // Create a temporary tarball from the input URI
        File tempTar = new File(context.getCacheDir(), "rootfs_import_temp.tar.gz");
        try {
            // Copy from URI to temp file
            ContentResolver contentResolver = context.getContentResolver();
            try (InputStream inputStream = contentResolver.openInputStream(inputUri);
                 FileOutputStream fos = new FileOutputStream(tempTar)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = inputStream.read(buffer)) > 0) {
                    fos.write(buffer, 0, count);
                }
            }
            
            // Remove existing rootfs and create fresh directory
            IOUtils.deleteDirectory(rootfsDir);
            ensureDir(rootfsDir);
            
            // Use system tar to extract (preserves symlinks and permissions)
            // cd into rootfs directory and extract archive there
            Shell shell = ShellUtil.newSh();
            Shell.Result result = shell.newJob()
                .add(String.format("cd '%s' && tar xzf '%s'", 
                    rootfsDir.getAbsolutePath(),
                    tempTar.getAbsolutePath()))
                .exec();
            
            if (!result.isSuccess()) {
                String errorMsg = result.getErr().isEmpty() ? "Unknown error" : String.join("\n", result.getErr());
                throw new IOException("tar extraction failed: " + errorMsg);
            }
        } finally {
            // Clean up temp file
            if (tempTar.exists()) {
                tempTar.delete();
            }
        }
    }
}
