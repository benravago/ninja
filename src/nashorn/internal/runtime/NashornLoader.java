/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
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

package nashorn.internal.runtime;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.PrivilegedAction;
import java.security.Permissions;
import java.security.SecureClassLoader;

import nashorn.internal.Util;

/**
 * Superclass for Nashorn class loader classes.
 */
abstract class NashornLoader extends SecureClassLoader {

    protected static final String OBJECTS_PKG        = "nashorn.internal.objects";
    protected static final String RUNTIME_PKG        = "nashorn.internal.runtime";
    protected static final String RUNTIME_ARRAYS_PKG = "nashorn.internal.runtime.arrays";
    protected static final String RUNTIME_LINKER_PKG = "nashorn.internal.runtime.linker";
    protected static final String SCRIPTS_PKG        = "nashorn.internal.scripts";
    protected static final String OBJECTS_PKG_INTERNAL        = "nashorn/internal/objects";
    protected static final String RUNTIME_PKG_INTERNAL        = "nashorn/internal/runtime";
    protected static final String RUNTIME_ARRAYS_PKG_INTERNAL = "nashorn/internal/runtime/arrays";
    protected static final String RUNTIME_LINKER_PKG_INTERNAL = "nashorn/internal/runtime/linker";
    protected static final String SCRIPTS_PKG_INTERNAL        = "nashorn/internal/scripts";

    static final Module NASHORN_MODULE = Context.class.getModule();

    private static final Permission[] SCRIPT_PERMISSIONS;

    private static final String MODULE_MANIPULATOR_NAME = SCRIPTS_PKG + ".ModuleGraphManipulator";
    private static final byte[] MODULE_MANIPULATOR_BYTES = readModuleManipulatorBytes();

    static {
        /*
         * Generated classes get access to runtime, runtime.linker, objects, scripts packages.
         * Note that the actual scripts can not access these because Java.type, Packages prevent these restricted packages.
         * And Java reflection and JSR292 access is prevented for scripts.
         * In other words, nashorn generated portions of script classes can access classes in these implementation packages.
         */
        SCRIPT_PERMISSIONS = new Permission[] {
            new RuntimePermission("accessClassInPackage." + RUNTIME_PKG),
            new RuntimePermission("accessClassInPackage." + RUNTIME_LINKER_PKG),
            new RuntimePermission("accessClassInPackage." + OBJECTS_PKG),
            new RuntimePermission("accessClassInPackage." + SCRIPTS_PKG),
            new RuntimePermission("accessClassInPackage." + RUNTIME_ARRAYS_PKG)
        };
    }

    // addExport Method object on ModuleGraphManipulator class loaded by this loader
    private Method addModuleExport;

    NashornLoader(ClassLoader parent) {
        super(parent);
    }

    void loadModuleManipulator() {
        var clazz = defineClass(MODULE_MANIPULATOR_NAME, MODULE_MANIPULATOR_BYTES, 0, MODULE_MANIPULATOR_BYTES.length);
        // force class initialization so that <clinit> runs!
        try {
            Class.forName(MODULE_MANIPULATOR_NAME, true, this);
        } catch (Exception ex) {
            Util.uncheck(ex);
        }
        final PrivilegedAction<Void> pa = () -> {
            try {
                addModuleExport = clazz.getDeclaredMethod("addExport", Module.class);
                addModuleExport.setAccessible(true);
            } catch (NoSuchMethodException | SecurityException ex) {
                Util.uncheck(ex);
            }
            return null;
        };
        AccessController.doPrivileged(pa);
    }

    final void addModuleExport(Module to) {
        try {
            addModuleExport.invoke(null, to);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
            Util.uncheck(ex);
        }
    }

    protected static void checkPackageAccess(String name) {
        var i = name.lastIndexOf('.');
        if (i != -1) {
            var sm = System.getSecurityManager();
            if (sm != null) {
                var pkgName = name.substring(0, i);
                switch (pkgName) {
                    case RUNTIME_PKG, RUNTIME_ARRAYS_PKG, RUNTIME_LINKER_PKG, OBJECTS_PKG, SCRIPTS_PKG -> {} // allow it.
                    default -> sm.checkPackageAccess(pkgName);
                }

            }
        }
    }

    @Override
    protected PermissionCollection getPermissions(CodeSource codesource) {
        var permCollection = new Permissions();
        for (var perm : SCRIPT_PERMISSIONS) {
            permCollection.add(perm);
        }
        return permCollection;
    }

    /**
     * Create a secure URL class loader for the given classpath
     * @param classPath classpath for the loader to search from
     * @param parent the parent class loader for the new class loader
     * @return the class loader
     */
    static ClassLoader createClassLoader(String classPath, ClassLoader parent) {
        var urls = pathToURLs(classPath);
        return URLClassLoader.newInstance(urls, parent);
    }

    /*
     * Utility method for converting a search path string to an array of directory and JAR file URLs.
     * @param path the search path string
     * @return the resulting array of directory and JAR file URLs
     */
    private static URL[] pathToURLs(String path) {
        var components = path.split(File.pathSeparator);
        var urls = new URL[components.length];
        var count = 0;
        while (count < components.length) {
            var url = fileToURL(new File(components[count]));
            if (url != null) {
                urls[count++] = url;
            }
        }
        if (urls.length != count) {
            var tmp = new URL[count];
            System.arraycopy(urls, 0, tmp, 0, count);
            urls = tmp;
        }
        return urls;
    }

    /*
     * Returns the directory or JAR file URL corresponding to the specified local file name.
     * @param file the File object
     * @return the resulting directory or JAR file URL, or null if unknown
     */
    private static URL fileToURL(File file) {
        String name;
        try {
            name = file.getCanonicalPath();
        } catch (IOException e) {
            name = file.getAbsolutePath();
        }
        name = name.replace(File.separatorChar, '/');
        if (!name.startsWith("/")) {
            name = "/" + name;
        }
        // If the file does not exist, then assume that it's a directory
        if (!file.isFile()) {
            name += "/";
        }
        try {
            return new URL("file", "", name);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("file");
        }
    }

    private static byte[] readModuleManipulatorBytes() {
        PrivilegedAction<byte[]> pa = () -> {
            var res = "/"+ MODULE_MANIPULATOR_NAME.replace('.', '/') + ".class";
            try (InputStream in = NashornLoader.class.getResourceAsStream(res)) {
                return in.readAllBytes();
            } catch (IOException exp) {
                throw new UncheckedIOException(exp);
            }
        };
        return AccessController.doPrivileged(pa);
    }

}

