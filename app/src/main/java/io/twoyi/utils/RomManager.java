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

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

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
    
    // Prefix used in some tarballs for host-absolute symlink paths
    private static final String HOST_ROOTFS_PREFIX = "/data/data/io.twoyi/rootfs/";
    
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
        try {
            // Extract and read rom.ini using Apache Commons Compress
            try (FileInputStream fis = new FileInputStream(rom);
                 GzipCompressorInputStream gzis = new GzipCompressorInputStream(fis);
                 TarArchiveInputStream tais = new TarArchiveInputStream(gzis)) {
                
                TarArchiveEntry entry;
                while ((entry = tais.getNextTarEntry()) != null) {
                    if (entry.getName().equals("rom.ini")) {
                        // Read rom.ini directly from the tar stream
                        byte[] content = new byte[(int) entry.getSize()];
                        int totalRead = 0;
                        while (totalRead < content.length) {
                            int read = tais.read(content, totalRead, content.length - totalRead);
                            if (read == -1) break;
                            totalRead += read;
                        }
                        ByteArrayInputStream bais = new ByteArrayInputStream(content);
                        return getRomInfo(bais);
                    }
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
        // This method is deprecated - use importRootfsFromTarball instead
        // Kept for compatibility, simply delegates to importRootfsFromTarball
        importRootfsFromTarball(context, Uri.fromFile(tarballFile));
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
        
        // Create a temporary tarball using Apache Commons Compress (preserves symlinks and permissions)
        File tempTar = new File(context.getCacheDir(), "rom.tar.gz");
        try {
            // Use Apache Commons Compress to create tar.gz archive
            try (FileOutputStream fos = new FileOutputStream(tempTar);
                 GzipCompressorOutputStream gzos = new GzipCompressorOutputStream(fos);
                 TarArchiveOutputStream taos = new TarArchiveOutputStream(gzos)) {
                
                taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                taos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                
                // Recursively add all files from rootfs directory
                addFilesToTarGz(rootfsDir, "", taos);
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
    
    private static void addFilesToTarGz(File file, String parent, TarArchiveOutputStream taos) throws IOException {
        String entryName = parent + file.getName();
        
        // Check if it's a symbolic link
        Path filePath = file.toPath();
        if (Files.isSymbolicLink(filePath)) {
            // Handle symbolic links - preserve them with relative paths
            Path linkTarget = Files.readSymbolicLink(filePath);
            TarArchiveEntry tarEntry = new TarArchiveEntry(entryName, TarArchiveEntry.LF_SYMLINK);
            // Store the link target as-is (should already be relative)
            tarEntry.setLinkName(linkTarget.toString());
            taos.putArchiveEntry(tarEntry);
            taos.closeArchiveEntry();
        } else if (file.isFile()) {
            TarArchiveEntry tarEntry = new TarArchiveEntry(file, entryName);
            taos.putArchiveEntry(tarEntry);
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = fis.read(buffer)) > 0) {
                    taos.write(buffer, 0, count);
                }
            }
            taos.closeArchiveEntry();
        } else if (file.isDirectory()) {
            TarArchiveEntry tarEntry = new TarArchiveEntry(file, entryName);
            taos.putArchiveEntry(tarEntry);
            taos.closeArchiveEntry();
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addFilesToTarGz(child, entryName + "/", taos);
                }
            }
        }
    }

    public static void importRootfsFromTarball(Context context, Uri inputUri) throws IOException {
        File rootfsDir = getRootfsDir(context);
        File importLog = new File(context.getCacheDir(), "import_tar.log");
        
        // Create a temporary tarball from the input URI in cache directory
        File tempTar = new File(context.getCacheDir(), "rom.tar.gz");
        try (FileWriter logWriter = new FileWriter(importLog)) {
            logWriter.write("=== Rootfs Import Log ===\n");
            logWriter.write("Timestamp: " + System.currentTimeMillis() + "\n");
            logWriter.write("Input URI: " + inputUri + "\n\n");
            
            // Copy from URI to temp file
            if ("file".equals(inputUri.getScheme())) {
                // Direct file URI - just use the file
                tempTar = new File(inputUri.getPath());
                logWriter.write("Using direct file: " + tempTar.getAbsolutePath() + "\n");
            } else {
                // Content URI - need to copy
                ContentResolver contentResolver = context.getContentResolver();
                try (InputStream inputStream = contentResolver.openInputStream(inputUri);
                     FileOutputStream fos = new FileOutputStream(tempTar)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    long totalBytes = 0;
                    while ((count = inputStream.read(buffer)) > 0) {
                        fos.write(buffer, 0, count);
                        totalBytes += count;
                    }
                    logWriter.write("Copied " + totalBytes + " bytes to temp file\n");
                }
            }
            
            logWriter.write("Tar file size: " + tempTar.length() + " bytes\n\n");
            
            // Remove existing rootfs and create fresh directory
            logWriter.write("Removing existing rootfs directory...\n");
            IOUtils.deleteDirectory(rootfsDir);
            ensureDir(rootfsDir);
            logWriter.write("Created fresh rootfs directory\n\n");
            
            // Use Apache Commons Compress to extract tar.gz archive
            logWriter.write("=== Extraction Log ===\n");
            int fileCount = 0, dirCount = 0, symlinkCount = 0;
            
            try (FileInputStream fis = new FileInputStream(tempTar);
                 GzipCompressorInputStream gzis = new GzipCompressorInputStream(fis);
                 TarArchiveInputStream tais = new TarArchiveInputStream(gzis)) {
                
                TarArchiveEntry entry;
                while ((entry = tais.getNextTarEntry()) != null) {
                    File outputFile = new File(rootfsDir, entry.getName());
                    
                    if (entry.isDirectory()) {
                        ensureDir(outputFile);
                        dirCount++;
                    } else if (entry.isSymbolicLink()) {
                        // Handle symbolic links - convert absolute container paths to relative
                        String linkName = entry.getLinkName();
                        Path linkTarget;
                        
                        if (Paths.get(linkName).isAbsolute()) {
                            // Absolute path in tar - need to determine what it refers to
                            String containerPath;
                            
                            // Check if this is a host-absolute path that includes a rootfs prefix
                            // Could be /data/data/io.twoyi/rootfs/, /data/user/0/io.twoyi/rootfs/, etc.
                            // We need to extract just the path after "/rootfs/"
                            int rootfsMarkerIndex = linkName.indexOf("/rootfs/");
                            if (rootfsMarkerIndex >= 0) {
                                // Extract the path after the rootfs marker
                                containerPath = linkName.substring(rootfsMarkerIndex + "/rootfs/".length());
                                logWriter.write("Symlink (host-abs): " + entry.getName() + " -> " + linkName + " (stripped to: " + containerPath + ")\n");
                            } else if (linkName.contains(HOST_ROOTFS_PREFIX)) {
                                // Fallback: check for exact hardcoded prefix (backward compat)
                                int rootfsIndex = linkName.indexOf(HOST_ROOTFS_PREFIX);
                                containerPath = linkName.substring(rootfsIndex + HOST_ROOTFS_PREFIX.length());
                                logWriter.write("Symlink (host-abs-prefix): " + entry.getName() + " -> " + linkName + " (stripped to: " + containerPath + ")\n");
                            } else {
                                // Standard container-absolute path like "/sbin/charger"
                                // This means "rootfs/sbin/charger"
                                containerPath = linkName.startsWith("/") ? linkName.substring(1) : linkName;
                            }
                            
                            // Get the symlink's parent directory
                            Path symlinkParent = outputFile.toPath().getParent();
                            // Get the absolute target path in the rootfs
                            Path absoluteTarget = rootfsDir.toPath().resolve(containerPath);
                            
                            try {
                                // Make relative path from symlink location to target
                                linkTarget = symlinkParent.relativize(absoluteTarget);
                            } catch (IllegalArgumentException e) {
                                // Fallback: relativize() can fail if paths are on different roots
                                // In this case, use the container path as-is which will create
                                // a symlink pointing to a path relative to the rootfs directory
                                linkTarget = Paths.get(containerPath);
                            }
                            logWriter.write("Symlink (abs->rel): " + entry.getName() + " => " + linkTarget + "\n");
                        } else {
                            // Already relative, use as-is
                            linkTarget = Paths.get(linkName);
                            logWriter.write("Symlink (rel): " + entry.getName() + " -> " + linkTarget + "\n");
                        }
                        
                        try {
                            // Delete if exists (force restore)
                            if (Files.exists(outputFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                                Files.delete(outputFile.toPath());
                            }
                            Files.createSymbolicLink(outputFile.toPath(), linkTarget);
                            symlinkCount++;
                        } catch (IOException e) {
                            Log.w(TAG, "Failed to create symlink: " + entry.getName() + " -> " + linkTarget, e);
                            logWriter.write("ERROR: Failed to create symlink: " + entry.getName() + " -> " + linkTarget + ": " + e.getMessage() + "\n");
                        }
                    } else {
                        // Regular file
                        File parent = outputFile.getParentFile();
                        if (parent != null) {
                            ensureDir(parent);
                        }
                        
                        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                            byte[] buffer = new byte[8192];
                            int count;
                            while ((count = tais.read(buffer)) > 0) {
                                outputStream.write(buffer, 0, count);
                            }
                        }
                        
                        // Set file permissions
                        int mode = entry.getMode();
                        if (mode != 0) {
                            outputFile.setExecutable((mode & 0100) != 0, false);
                            outputFile.setReadable((mode & 0400) != 0, false);
                            outputFile.setWritable((mode & 0200) != 0, false);
                        }
                        fileCount++;
                    }
                }
            }
            
            logWriter.write("\n=== Extraction Summary ===\n");
            logWriter.write("Files extracted: " + fileCount + "\n");
            logWriter.write("Directories created: " + dirCount + "\n");
            logWriter.write("Symlinks created: " + symlinkCount + "\n");
            logWriter.write("Import completed successfully\n");
            
        } catch (IOException e) {
            try (FileWriter logWriter = new FileWriter(importLog, true)) {
                logWriter.write("\n=== ERROR ===\n");
                logWriter.write("Import failed: " + e.getMessage() + "\n");
            } catch (IOException ignored) {}
            throw e;
        } finally {
            // Clean up temp file (only if we created it)
            if (!"file".equals(inputUri.getScheme()) && tempTar.exists()) {
                tempTar.delete();
            }
        }
    }
}
