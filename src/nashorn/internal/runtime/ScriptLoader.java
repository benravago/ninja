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

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Modifier;
import java.security.CodeSource;
import java.util.Objects;
import java.util.Set;

/**
 * Responsible for loading script generated classes.
 */
final class ScriptLoader extends NashornLoader {

    private static final String NASHORN_PKG_PREFIX = "nashorn.internal.";

    private volatile boolean structureAccessAdded;
    private final Context context;
    private final Module scriptModule;

    Context getContext() {
        return context;
    }

    /**
     * Constructor.
     */
    ScriptLoader(Context context) {
        super(context.getStructLoader());
        this.context = context;

        // new scripts module, it's specific exports and read-edges
        scriptModule = createModule("nashorn.scripting.scripts");

        // specific exports from nashorn to new scripts module
        NASHORN_MODULE.addExports(OBJECTS_PKG, scriptModule);
        NASHORN_MODULE.addExports(RUNTIME_PKG, scriptModule);
        NASHORN_MODULE.addExports(RUNTIME_ARRAYS_PKG, scriptModule);
        NASHORN_MODULE.addExports(RUNTIME_LINKER_PKG, scriptModule);
        NASHORN_MODULE.addExports(SCRIPTS_PKG, scriptModule);

        // nashorn needs to read scripts module methods,fields
        NASHORN_MODULE.addReads(scriptModule);
    }

    private Module createModule(String moduleName) {
        var structMod = context.getStructLoader().getModule();
        var builder = ModuleDescriptor.newModule(moduleName,
            Set.of(Modifier.SYNTHETIC))
                .requires("java.logging") // TODO: replace with System.Logger
                .requires(NASHORN_MODULE.getName())
                .requires(structMod.getName())
                .packages(Set.of(SCRIPTS_PKG));

        var descriptor = builder.build();

        var mod = Context.createModuleTrusted(structMod.getLayer(), descriptor, this);
        loadModuleManipulator();
        return mod;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        checkPackageAccess(name);
        var cl = super.loadClass(name, resolve);
        if (!structureAccessAdded) {
            var structLoader = context.getStructLoader();
            if (cl.getClassLoader() == structLoader) {
                structureAccessAdded = true;
                structLoader.addModuleExport(scriptModule);
            }
        }
        return cl;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        var appLoader = context.getAppLoader();

        /*
         * If the appLoader is null, don't bother side-delegating to it!
         * Bootloader has been already attempted via parent loader delegation from the "loadClass" method.
         * Also, make sure that we don't delegate to the app loader for nashorn's own classes or nashorn generated classes!
         */
        if (appLoader == null || name.startsWith(NASHORN_PKG_PREFIX)) {
            throw new ClassNotFoundException(name);
        }

        /*
         * This split-delegation is used so that caller loader based resolutions of classes would work.
         * For example, java.sql.DriverManager uses caller's class loader to get Driver instances.
         * Without this split-delegation a script class evaluating DriverManager.getDrivers() will not get back any JDBC driver!
         */
        return appLoader.loadClass(name);
    }

    // package-private and private stuff below this point

    /**
     * Install a class for use by the Nashorn runtime
     * @param name Binary name of class.
     * @param data Class data bytes.
     * @param cs CodeSource code source of the class bytes.
     * @return Installed class.
     */
    synchronized Class<?> installClass(String name, byte[] data, CodeSource cs) {
        return defineClass(name, data, 0, data.length, Objects.requireNonNull(cs));
    }

}
