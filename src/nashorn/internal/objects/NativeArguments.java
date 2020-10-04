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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import java.util.ArrayList;
import java.util.Arrays;

import nashorn.internal.runtime.AccessorProperty;
import nashorn.internal.runtime.Property;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptFunction;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.arrays.ArrayData;
import static nashorn.internal.lookup.Lookup.MH;
import static nashorn.internal.runtime.ScriptRuntime.UNDEFINED;

/**
 * ECMA 10.6 Arguments Object.
 *
 * Arguments object for functions.
 */
public final class NativeArguments extends ScriptObject {

    private static final MethodHandle G$LENGTH = findOwnMH("G$length", Object.class, Object.class);

    private static final MethodHandle S$LENGTH = findOwnMH("S$length", void.class, Object.class, Object.class);

    private static final PropertyMap map$;

    static {
        var properties = new ArrayList<Property>(1);
        properties.add(AccessorProperty.create("length", Property.NOT_ENUMERABLE, G$LENGTH, S$LENGTH));

        var map = PropertyMap.newMap(properties);
        // The caller and callee properties should throw TypeError.
        // Need to add properties directly to map since slots are assigned speculatively by newUserAccessors.
        var flags = Property.NOT_ENUMERABLE | Property.NOT_CONFIGURABLE;

        map = map.addPropertyNoHistory(map.newUserAccessors("caller", flags));
        map = map.addPropertyNoHistory(map.newUserAccessors("callee", flags));
        map$ = map;
    }

    static PropertyMap getInitialMap() {
        return map$;
    }

    private Object   length;
    private final Object[] namedArgs;

    NativeArguments(Object[] values, int numParams,final ScriptObject proto, PropertyMap map) {
        super(proto, map);
        setIsArguments();

        var func = Global.instance().getTypeErrorThrower();
        // We have to fill user accessor functions late as these are stored in this object rather than in the PropertyMap of this object.
        initUserAccessors("caller", func, func);
        initUserAccessors("callee", func, func);

        setArray(ArrayData.allocate(values));
        this.length = values.length;

        // extend/truncate named arg array as needed and copy values
        this.namedArgs = new Object[numParams];
        if (numParams > values.length) {
            Arrays.fill(namedArgs, UNDEFINED);
        }
        System.arraycopy(values, 0, namedArgs, 0, Math.min(namedArgs.length, values.length));
    }

    @Override
    public String getClassName() {
        return "Arguments";
    }

    /**
     * getArgument is used for named argument access.
     */
    @Override
    public Object getArgument(int key) {
        return (key >=0 && key < namedArgs.length) ? namedArgs[key] : UNDEFINED;
    }

    /**
     * setArgument is used for named argument set.
     */
    @Override
    public void setArgument(int key, Object value) {
        if (key >= 0 && key < namedArgs.length) {
            namedArgs[key] = value;
        }
    }

    /**
     * Length getter
     */
    public static Object G$length(Object self) {
        if (self instanceof NativeArguments) {
            return ((NativeArguments)self).getArgumentsLength();
        }
        return 0;
    }

    /**
     * Length setter
     */
    public static void S$length(Object self, Object value) {
        if (self instanceof NativeArguments) {
            ((NativeArguments)self).setArgumentsLength(value);
        }
    }

    private Object getArgumentsLength() {
        return length;
    }

    private void setArgumentsLength(Object length) {
        this.length = length;
    }

    private static MethodHandle findOwnMH(String name, Class<?> rtype, Class<?>... types) {
        return MH.findStatic(MethodHandles.lookup(), NativeArguments.class, name, MH.type(rtype, types));
    }

    /**
     * Factory to create correct Arguments object.
     */
    public static ScriptObject allocate(Object[] arguments, ScriptFunction callee, int numParams) {
        // Functions won't always have a callee for arguments, and may pass null instead.
        var global = Global.instance();
        var proto = global.getObjectPrototype();
        return new NativeArguments(arguments, numParams, proto, getInitialMap());
    }

}
