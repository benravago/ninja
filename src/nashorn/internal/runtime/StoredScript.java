/*
 * Copyright (c) 2010, 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Class representing a persistent compiled script.
 */
public final class StoredScript implements Serializable {

    /** Compilation id */
    private final int compilationId;

    /** Main class name. */
    private final String mainClassName;

    /** Map of class names to class bytes. */
    @SuppressWarnings("serial") // Not statically typed as Serializable
    private final Map<String, byte[]> classBytes;

    /** Constants array. */
    @SuppressWarnings("serial") // Not statically typed as Serializable
    private final Object[] constants;

    /** Function initializers */
    @SuppressWarnings("serial") // Not statically typed as Serializable
    private final Map<Integer, FunctionInitializer> initializers;


    /**
     * Constructor.
     * @param compilationId compilation id
     * @param mainClassName main class name
     * @param classBytes map of class names to class bytes
     * @param initializers initializer map, id -&gt; FunctionInitializer
     * @param constants constants array
     */
    public StoredScript(int compilationId, String mainClassName, Map<String, byte[]> classBytes, Map<Integer, FunctionInitializer> initializers, Object[] constants) {
        this.compilationId = compilationId;
        this.mainClassName = mainClassName;
        this.classBytes = classBytes;
        this.constants = constants;
        this.initializers = initializers;
    }

    /**
     * Get the compilation id for this StoredScript
     * @return compilation id
     */
    public int getCompilationId() {
        return compilationId;
    }

    private Map<String, Class<?>> installClasses(Source source, CodeInstaller installer) {
        var installedClasses = new HashMap<String, Class<?>>();
        var mainClassBytes = classBytes.get(mainClassName);
        var mainClass = installer.install(mainClassName, mainClassBytes);

        installedClasses.put(mainClassName, mainClass);

        for (var entry : classBytes.entrySet()) {
            var className = entry.getKey();

            if (!className.equals(mainClassName)) {
                installedClasses.put(className, installer.install(className, entry.getValue()));
            }
        }

        installer.initialize(installedClasses.values(), source, constants);
        return installedClasses;
    }

    FunctionInitializer installFunction(RecompilableScriptFunctionData data, CodeInstaller installer) {
        var installedClasses = installClasses(data.getSource(), installer);

        assert initializers != null;
        assert initializers.size() == 1;
        var initializer = initializers.values().iterator().next();

        for (var i = 0; i < constants.length; i++) {
            if (constants[i] instanceof RecompilableScriptFunctionData) {
                // replace deserialized function data with the ones we already have
                var newData = data.getScriptFunctionData(((RecompilableScriptFunctionData) constants[i]).getFunctionNodeId());
                assert newData != null;
                newData.initTransients(data.getSource(), installer);
                constants[i] = newData;
            }
        }

        initializer.setCode(installedClasses.get(initializer.getClassName()));
        return initializer;
    }

    /**
     * Install as script.
     * @param source the source
     * @param installer the installer
     * @return main script class
     */
    Class<?> installScript(Source source, CodeInstaller installer) {

        var installedClasses = installClasses(source, installer);

        for (var constant : constants) {
            if (constant instanceof RecompilableScriptFunctionData) {
                var data = (RecompilableScriptFunctionData) constant;
                data.initTransients(source, installer);
                var initializer = initializers.get(data.getFunctionNodeId());
                if (initializer != null) {
                    initializer.setCode(installedClasses.get(initializer.getClassName()));
                    data.initializeCode(initializer);
                }
            }
        }

        return installedClasses.get(mainClassName);
    }

    @Override
    public int hashCode() {
        var hash = mainClassName.hashCode();
        hash = 31 * hash + classBytes.hashCode();
        hash = 31 * hash + Arrays.hashCode(constants);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof StoredScript)) {
            return false;
        }

        var cs = (StoredScript) obj;
        return mainClassName.equals(cs.mainClassName) && classBytes.equals(cs.classBytes) && Arrays.equals(constants, cs.constants);
    }

}
