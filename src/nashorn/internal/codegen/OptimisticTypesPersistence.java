/*
 * Copyright (c) 2010, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package nashorn.internal.codegen;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;

import java.net.URI;
import java.net.URL;

import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;

import java.security.AccessController;
import java.security.MessageDigest;
import java.security.PrivilegedAction;

import java.text.SimpleDateFormat;

import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import nashorn.internal.codegen.types.Type;
import nashorn.internal.runtime.Context;
import nashorn.internal.runtime.RecompilableScriptFunctionData;
import nashorn.internal.runtime.Source;
import nashorn.internal.runtime.logging.DebugLogger;
import nashorn.internal.runtime.options.Options;

/**
 * Static utility that encapsulates persistence of type information for functions compiled with optimistic typing.
 * <p>
 * With this feature enabled, when a JavaScript function is recompiled because it gets deoptimized, the type information for deoptimization is stored in a cache file.
 * If the same function is compiled in a subsequent JVM invocation, the type information is used for initial compilation, thus allowing the system to skip a lot of intermediate recompilations and immediately emit a version of the code that has its optimistic types at (or near) the steady state.
 * <p>
 * Normally, the type info persistence feature is disabled.
 * When the {@code nashorn.typeInfo.maxFiles} system property is specified with a value greater than 0, it is enabled and operates in an operating-system specific per-user cache directory.
 * You can override the directory by specifying it in the {@code nashorn.typeInfo.cacheDir} directory.
 * The maximum number of files is softly enforced by a task that cleans up the directory periodically on a separate thread.
 * It is run after some delay after a new file is added to the cache. The default delay is 20 seconds, and can be set using the {@code nashorn.typeInfo.cleanupDelaySeconds} system property.
 * You can also specify the word {@code unlimited} as the value for {@code nashorn.typeInfo.maxFiles} in which case the type info cache is allowed to grow without limits.
 */
public final class OptimisticTypesPersistence {

    // Default is 0, for disabling the feature when not specified.
    // A reasonable default when enabled is dependent on the application; setting it to e.g. 20000 is probably good enough for most uses and will usually cap the cache directory to about 80MB presuming a 4kB filesystem allocation unit.
    // There is one file per JavaScript function.
    private static final int DEFAULT_MAX_FILES = 0;
    // Constants for signifying that the cache should not be limited
    private static final int UNLIMITED_FILES = -1;
    // Maximum number of files that should be cached on disk. The maximum will be softly enforced.
    private static final int MAX_FILES = getMaxFiles();
    // Number of seconds to wait between adding a new file to the cache and running a cleanup process
    private static final int DEFAULT_CLEANUP_DELAY = 20;
    private static final int CLEANUP_DELAY = Math.max(0, Options.getIntProperty("nashorn.typeInfo.cleanupDelaySeconds", DEFAULT_CLEANUP_DELAY));
    // The name of the default subdirectory within the system cache directory where we store type info.
    private static final String DEFAULT_CACHE_SUBDIR_NAME = "nashorn.typeinfo.Cache";
    // The directory where we cache type info
    private static final File baseCacheDir = createBaseCacheDir();
    private static final File cacheDir = createCacheDir(baseCacheDir);
    // In-process locks to make sure we don't have a cross-thread race condition manipulating any file.
    private static final Object[] locks = cacheDir == null ? null : createLockArray();
    // Only report one read/write error every minute
    private static final long ERROR_REPORT_THRESHOLD = 60000L;

    private static volatile long lastReportedError;
    private static final AtomicBoolean scheduledCleanup;
    private static final Timer cleanupTimer;
    static {
        if (baseCacheDir == null || MAX_FILES == UNLIMITED_FILES) {
            scheduledCleanup = null;
            cleanupTimer = null;
        } else {
            scheduledCleanup = new AtomicBoolean();
            cleanupTimer = new Timer(true);
        }
    }
    /**
     * Retrieves an opaque descriptor for the persistence location for a given function.
     * It should be passed to {@link #load(Object)} and {@link #store(Object, Map)} methods.
     * @param source the source where the function comes from
     * @param functionId the unique ID number of the function within the source
     * @param paramTypes the types of the function parameters (as persistence is per parameter type specialization).
     * @return an opaque descriptor for the persistence location. Can be null if persistence is disabled.
     */
    public static Object getLocationDescriptor(Source source, int functionId, Type[] paramTypes) {
        if (cacheDir == null) {
            return null;
        }
        var b = new StringBuilder(48);
        // Base64-encode the digest of the source, and append the function id.
        b.append(source.getDigest()).append('-').append(functionId);
        // Finally, if this is a parameter-type specialized version of the function, add the parameter types to the file name.
        if (paramTypes != null && paramTypes.length > 0) {
            b.append('-');
            for (var t: paramTypes) {
                b.append(Type.getShortSignatureDescriptor(t));
            }
        }
        return new LocationDescriptor(new File(cacheDir, b.toString()));
    }

    private static final class LocationDescriptor {
        private final File file;

        LocationDescriptor(File file) {
            this.file = file;
        }
    }


    /**
     * Stores the map of optimistic types for a given function.
     * @param locationDescriptor the opaque persistence location descriptor, retrieved by calling {@link #getLocationDescriptor(Source, int, Type[])}.
     * @param optimisticTypes the map of optimistic types.
     */
    @SuppressWarnings("resource")
    public static void store(Object locationDescriptor, Map<Integer, Type> optimisticTypes) {
        if (locationDescriptor == null || optimisticTypes.isEmpty()) {
            return;
        }
        var file = ((LocationDescriptor)locationDescriptor).file;

        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            synchronized (getFileLock(file)) {
                if (!file.exists()) {
                    // If the file already exists, we aren't increasing the number of cached files, so don't schedule cleanup.
                    scheduleCleanup();
                }
                try (var out = new FileOutputStream(file)) {
                    out.getChannel().lock(); // lock exclusive
                    var dout = new DataOutputStream(new BufferedOutputStream(out));
                    Type.writeTypeMap(optimisticTypes, dout);
                    dout.flush();
                } catch(Exception e) {
                    reportError("write", file, e);
                }
            }
            return null;
        });
    }

    /**
     * Loads the map of optimistic types for a given function.
     * @param locationDescriptor the opaque persistence location descriptor, retrieved by calling {@link #getLocationDescriptor(Source, int, Type[])}.
     * @return the map of optimistic types, or null if persisted type information could not be retrieved.
     */
    @SuppressWarnings("resource")
    public static Map<Integer, Type> load(Object locationDescriptor) {
        if (locationDescriptor == null) {
            return null;
        }
        var file = ((LocationDescriptor)locationDescriptor).file;
        return AccessController.doPrivileged((PrivilegedAction<Map<Integer, Type>>) () -> {
            try {
                if (!file.isFile()) {
                    return null;
                }
                synchronized (getFileLock(file)) {
                    try (var in = new FileInputStream(file)) {
                        in.getChannel().lock(0, Long.MAX_VALUE, true); // lock shared
                        var din = new DataInputStream(new BufferedInputStream(in));
                        return Type.readTypeMap(din);
                    }
                }
            } catch (Exception e) {
                reportError("read", file, e);
                return null;
            }
        });
    }

    private static void reportError(String msg, File file, Exception e) {
        var now = System.currentTimeMillis();
        if (now - lastReportedError > ERROR_REPORT_THRESHOLD) {
            reportError(String.format("Failed to %s %s", msg, file), e);
            lastReportedError = now;
        }
    }

    /**
     * Logs an error message with warning severity (reasoning being that we're reporting an error that'll disable the type info cache, but it's only logged as a warning because that doesn't prevent Nashorn from running, it just disables a performance-enhancing cache).
     * @param msg the message to log
     * @param e the exception that represents the error.
     */
    private static void reportError(String msg, Exception e) {
        getLogger().warning(msg, "\n", exceptionToString(e));
    }

    /**
     * A helper that prints an exception stack trace into a string.
     * We have to do this as if we just pass the exception to {@link DebugLogger#warning(Object...)}, it will only log the exception message and not the stack, making problems harder to diagnose.
     * @param e the exception
     * @return the string representation of {@link Exception#printStackTrace()} output.
     */
    private static String exceptionToString(Exception e) {
        var sw = new StringWriter();
        var pw = new PrintWriter(sw, false);
        e.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static File createBaseCacheDir() {
        if (MAX_FILES == 0 || Options.getBooleanProperty("nashorn.typeInfo.disabled")) {
            return null;
        }
        try {
            return createBaseCacheDirPrivileged();
        } catch(Exception e) {
            reportError("Failed to create cache dir", e);
            return null;
        }
    }

    private static File createBaseCacheDirPrivileged() {
        return AccessController.doPrivileged((PrivilegedAction<File>) () -> {
            var explicitDir = System.getProperty("nashorn.typeInfo.cacheDir");
            File dir;
            if (explicitDir != null) {
                dir = new File(explicitDir);
            } else {
                // When no directory is explicitly specified, get an operating system specific cache directory, and create "com.oracle.java.NashornTypeInfo" in it.
                var systemCacheDir = getSystemCacheDir();
                dir = new File(systemCacheDir, DEFAULT_CACHE_SUBDIR_NAME);
                if (isSymbolicLink(dir)) {
                    return null;
                }
            }
            return dir;
        });
    }

    private static File createCacheDir(File baseDir) {
        if (baseDir == null) {
            return null;
        }
        try {
            return createCacheDirPrivileged(baseDir);
        } catch(Exception e) {
            reportError("Failed to create cache dir", e);
            return null;
        }
    }

    private static File createCacheDirPrivileged(File baseDir) {
        return AccessController.doPrivileged((PrivilegedAction<File>) () -> {
            String versionDirName;
            try {
                versionDirName = getVersionDirName();
            } catch(Exception e) {
                reportError("Failed to calculate version dir name", e);
                return null;
            }
            var versionDir = new File(baseDir, versionDirName);
            if (isSymbolicLink(versionDir)) {
                return null;
            }
            versionDir.mkdirs();
            if (versionDir.isDirectory()) {
                // FIXME: Logger is disabled as Context.getContext() always returns null here because global scope object will not be created by the time this method gets invoked
                getLogger().info("Optimistic type persistence directory is " + versionDir);
                return versionDir;
            }
            getLogger().warning("Could not create optimistic type persistence directory " + versionDir);
            return null;
        });
    }

    /**
     * Returns an operating system specific root directory for cache files.
     */
    private static File getSystemCacheDir() {
        var os = System.getProperty("os.name", "generic");
        if ("Mac OS X".equals(os)) {
            // Mac OS X stores caches in ~/Library/Caches
            return new File(new File(System.getProperty("user.home"), "Library"), "Caches");
        } else if (os.startsWith("Windows")) {
            // On Windows, temp directory is the best approximation of a cache directory, as its contents persist across reboots and various cleanup utilities know about it. java.io.tmpdir normally points to a user-specific temp directory, %HOME%\LocalSettings\Temp.
            return new File(System.getProperty("java.io.tmpdir"));
        } else {
            // In other cases we're presumably dealing with a UNIX flavor (Linux, Solaris, etc.); "~/.cache"
            return new File(System.getProperty("user.home"), ".cache");
        }
    }

    private static final String ANCHOR_PROPS = "anchor.properties";

    /**
     * In order to ensure that changes in Nashorn code don't cause corruption in the data, we'll create a per-code-version directory.
     * Normally, this will create the SHA-1 digest of the nashorn.jar.
     * In case the classpath for nashorn is local directory (e.g. during development), this will create the string "dev-" followed by the timestamp of the most recent .class file.
     * @return digest of currently running nashorn
     * @throws Exception if digest could not be created
     */
    public static String getVersionDirName() throws Exception {
        // NOTE: getResource("") won't work if the JAR file doesn't have directory entries (and JAR files in JDK distro don't, or at least it's a bad idea counting on it).
        // Alternatively, we could've tried getResource("OptimisticTypesPersistence.class") but behavior of getResource with regard to its willingness to hand out URLs to .class files is also unspecified.
        // Therefore, the most robust way to obtain an URL to our package is to have a small non-class anchor file and start out from its URL.
        var url = OptimisticTypesPersistence.class.getResource(ANCHOR_PROPS);
        var protocol = url.getProtocol();
        if (protocol.equals("jar")) {
            // Normal deployment: nashorn.jar
            var jarUrlFile = url.getFile();
            var filePath = jarUrlFile.substring(0, jarUrlFile.indexOf('!'));
            var file = new URL(filePath);
            try (var in = file.openStream()) {
                var buf = new byte[128*1024];
                var digest = MessageDigest.getInstance("SHA-1");
                for (;;) {
                    var l = in.read(buf);
                    if (l == -1) {
                        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
                    }
                    digest.update(buf, 0, l);
                }
            }
        } else if (protocol.equals("file")) {
            // Development
            var fileStr = url.getFile();
            var className = OptimisticTypesPersistence.class.getName();
            var packageNameLen = className.lastIndexOf('.');
            var dirStr = fileStr.substring(0, fileStr.length() - packageNameLen - 1 - ANCHOR_PROPS.length());
            var dir = new File(dirStr);
            return "dev-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(getLastModifiedClassFile(dir, 0L)));
        } else if (protocol.equals("jrt")) {
            return getJrtVersionDirName();
        } else {
            throw new AssertionError("unknown protocol");
        }
    }

    private static long getLastModifiedClassFile(File dir, long max) {
        var currentMax = max;
        for (var f : dir.listFiles()) {
            if (f.getName().endsWith(".class")) {
                var lastModified = f.lastModified();
                if (lastModified > currentMax) {
                    currentMax = lastModified;
                }
            } else if (f.isDirectory()) {
                var lastModified = getLastModifiedClassFile(f, currentMax);
                if (lastModified > currentMax) {
                    currentMax = lastModified;
                }
            }
        }
        return currentMax;
    }

    /**
     * Returns true if the specified file is a symbolic link, and also logs a warning if it is.
     */
    private static boolean isSymbolicLink(File file) {
        if (Files.isSymbolicLink(file.toPath())) {
            getLogger().warning("Directory " + file + " is a symlink");
            return true;
        }
        return false;
    }

    private static Object[] createLockArray() {
        var lockArray = new Object[Runtime.getRuntime().availableProcessors() * 2];
        for (var i = 0; i < lockArray.length; ++i) {
            lockArray[i] = new Object();
        }
        return lockArray;
    }

    private static Object getFileLock(File file) {
        return locks[(file.hashCode() & Integer.MAX_VALUE) % locks.length];
    }

    private static DebugLogger getLogger() {
        try {
            return Context.getContext().getLogger(RecompilableScriptFunctionData.class);
        } catch (NullPointerException e) {
            // Don't print stacktrace until we revisit this, NPE is a known issue here
        } catch (Exception e) {
            e.printStackTrace();
        }
        return DebugLogger.DISABLED_LOGGER;
    }

    private static void scheduleCleanup() {
        if (MAX_FILES != UNLIMITED_FILES && scheduledCleanup.compareAndSet(false, true)) {
            cleanupTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    scheduledCleanup.set(false);
                    try {
                        doCleanup();
                    } catch (IOException e) {
                        // Ignore it. While this is unfortunate, we don't have good facility for reporting this, as we're running in a thread that has no access to Context, so we can't grab a DebugLogger.
                    }
                }
            }, TimeUnit.SECONDS.toMillis(CLEANUP_DELAY));
        }
    }

    private static void doCleanup() throws IOException {
        var files = getAllRegularFilesInLastModifiedOrder();
        var nFiles = files.length;
        var filesToDelete = Math.max(0, nFiles - MAX_FILES);
        var filesDeleted = 0;
        for (var i = 0; i < nFiles && filesDeleted < filesToDelete; ++i) {
            try {
                Files.deleteIfExists(files[i]);
                // Even if it didn't exist, we increment filesDeleted; it existed a moment earlier; something else deleted it for us; that's okay with us.
                filesDeleted++;
            } catch (Exception e) {
                // does not increase filesDeleted
            }
            files[i] = null; // gc eligible
        }
    }

    private static Path[] getAllRegularFilesInLastModifiedOrder() throws IOException {
        try (var filesStream = Files.walk(baseCacheDir.toPath())) {
            // TODO: rewrite below once we can use JDK8 syntactic constructs
            return filesStream
                .filter(new Predicate<Path>() {
                    @Override
                    public boolean test(Path path) {
                        return !Files.isDirectory(path);
                    }
                })
                .map(new Function<Path, PathAndTime>() {
                    @Override
                    public PathAndTime apply(Path path) {
                        return new PathAndTime(path);
                    }
                })
                .sorted()
                .map(new Function<PathAndTime, Path>() {
                    @Override
                    public Path apply(PathAndTime pathAndTime) {
                        return pathAndTime.path;
                    }
                })
                .toArray(new IntFunction<Path[]>() { // Replace with Path::new
                    @Override
                    public Path[] apply(int length) {
                        return new Path[length];
                    }
                });
        }
    }

    private static class PathAndTime implements Comparable<PathAndTime> {
        private final Path path;
        private final long time;

        PathAndTime(Path path) {
            this.path = path;
            this.time = getTime(path);
        }

        @Override
        public int compareTo(PathAndTime other) {
            return Long.compare(time, other.time);
        }

        private static long getTime(Path path) {
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                // All files for which we can't retrieve the last modified date will be considered oldest.
                return -1L;
            }
        }
    }

    private static int getMaxFiles() {
        var str = Options.getStringProperty("nashorn.typeInfo.maxFiles", null);
        if (str == null) {
            return DEFAULT_MAX_FILES;
        } else if ("unlimited".equals(str)) {
            return UNLIMITED_FILES;
        }
        return Math.max(0, Integer.parseInt(str));
    }

    private static final String JRT_NASHORN_DIR = "/modules/nashorn.scripting";

    // version directory name if nashorn is loaded from jrt:/ URL
    private static String getJrtVersionDirName() throws Exception {
        var fs = getJrtFileSystem();
        // consider all .class resources under nashorn module to compute checksum
        var nashorn = fs.getPath(JRT_NASHORN_DIR);
        if (! Files.isDirectory(nashorn)) {
            throw new FileNotFoundException("missing " + JRT_NASHORN_DIR + " dir in jrt fs");
        }
        var digest = MessageDigest.getInstance("SHA-1");
        Files.walk(nashorn).forEach(new Consumer<Path>() {
            @Override
            public void accept(Path p) {
                // take only the .class resources.
                if (Files.isRegularFile(p) && p.toString().endsWith(".class")) {
                    try {
                        digest.update(Files.readAllBytes(p));
                    } catch (IOException ioe) {
                        throw new UncheckedIOException(ioe);
                    }
                }
            }
        });
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    }

    // get the default jrt FileSystem instance
    private static FileSystem getJrtFileSystem() {
        return AccessController.doPrivileged((PrivilegedAction<FileSystem>) () ->
            FileSystems.getFileSystem(URI.create("jrt:/")));
    }

}
