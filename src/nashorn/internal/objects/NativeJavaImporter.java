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

package nashorn.internal.objects;

import jdk.dynalink.beans.StaticClass;

import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.runtime.Context;
import nashorn.internal.runtime.FindProperty;
import nashorn.internal.runtime.NativeJavaPackage;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;

/**
 * This is "JavaImporter" constructor.
 *
 * This constructor allows you to use Java types omitting explicit package names.
 * Objects of this constructor are used along with {@code "with"} statements and as such are not usable in ECMAScript mode.
 *
 * <p>
 * Example:
 * <pre>
 *     var imports = new JavaImporter(java.util, java.io);
 *     with (imports) {
 *         var m = new HashMap(); // java.util.HashMap
 *         var f = new File("."); // java.io.File
 *         ...
 *     }
 * </pre>
 *
 * Note however that the preferred way for accessing Java types in Nashorn is through the use of {@link NativeJava#type(Object, Object) Java.type()} method.
 */
@ScriptClass("JavaImporter")
public final class NativeJavaImporter extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private final Object[] args;

    private NativeJavaImporter(Object[] args, ScriptObject proto, PropertyMap map) {
        super(proto, map);
        this.args = args;
    }

    private NativeJavaImporter(Object[] args, Global global) {
        this(args, global.getJavaImporterPrototype(), $nasgenmap$);
    }

    private NativeJavaImporter(Object[] args) {
        this(args, Global.instance());
    }

    @Override
    public String getClassName() {
        return "JavaImporter";
    }

    @Constructor(arity = 1)
    public static NativeJavaImporter constructor(boolean isNew, Object self, Object... args) {
        return new NativeJavaImporter(args);
    }

    @Override
    protected FindProperty findProperty(Object key, boolean deep, boolean isScope, ScriptObject start) {
        var find = super.findProperty(key, deep, isScope, start);
        if (find == null && key instanceof String) {
            var name = (String) key;
            var value = createProperty(name);
            if (value != null) {
                // We must avoid calling findProperty recursively, so we pass null as first argument
                setObject(null, 0, key, value);
                return super.findProperty(key, deep, isScope, start);
            }
        }
        return find;
    }

    private Object createProperty(String name) {
        var len = args.length;

        for (var i = len - 1; i > -1; i--) {
            var obj = args[i];

            if (obj instanceof StaticClass) {
                if (((StaticClass)obj).getRepresentedClass().getSimpleName().equals(name)) {
                    return obj;
                }
            } else if (obj instanceof NativeJavaPackage) {
                var pkgName  = ((NativeJavaPackage)obj).getName();
                var fullName = pkgName.isEmpty() ? name : (pkgName + "." + name);
                var context = Global.instance().getContext();
                try {
                    return StaticClass.forClass(context.findClass(fullName));
                } catch (ClassNotFoundException e) {
                    // IGNORE
                }
            }
        }
        return null;
    }

}
