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
import java.lang.invoke.MethodType;

import jdk.dynalink.linker.GuardedInvocation;
import jdk.dynalink.linker.LinkRequest;

import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.linker.PrimitiveLookup;
import static nashorn.internal.lookup.Lookup.MH;
import static nashorn.internal.runtime.ECMAErrors.typeError;

/**
 * ECMA 15.6 Boolean Objects.
 */
@ScriptClass("Boolean")
public final class NativeBoolean extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private final boolean value;

    /** Method handle to create an object wrapper for a primitive boolean. */
    static final MethodHandle WRAPFILTER = findOwnMH("wrapFilter", MH.type(NativeBoolean.class, Object.class));

    /** Method handle to retrieve the Boolean prototype object. */
    private static final MethodHandle PROTOFILTER = findOwnMH("protoFilter", MH.type(Object.class, Object.class));

    private NativeBoolean(boolean value, ScriptObject proto, PropertyMap map) {
        super(proto, map);
        this.value = value;
    }

    NativeBoolean(boolean flag, Global global) {
        this(flag, global.getBooleanPrototype(), $nasgenmap$);
    }

    NativeBoolean(boolean flag) {
        this(flag, Global.instance());
    }

    @Override
    public String safeToString() {
        return "[Boolean " + toString() + "]";
    }

    @Override
    public String toString() {
        return Boolean.toString(getValue());
    }

    /**
     * Get the value for this NativeBoolean
     */
    public boolean getValue() {
        return booleanValue();
    }

    /**
     * Get the value for this NativeBoolean
     */
    public boolean booleanValue() {
        return value;
    }

    @Override
    public String getClassName() {
        return "Boolean";
    }

    /**
     * ECMA 15.6.4.2 Boolean.prototype.toString ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static String toString(Object self) {
        return getBoolean(self).toString();
    }

    /**
     * ECMA 15.6.4.3 Boolean.prototype.valueOf ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static boolean valueOf(Object self) {
        return getBoolean(self);
    }

    /**
     * ECMA 15.6.2.1 new Boolean (value)
     */
    @Constructor(arity = 1)
    public static Object constructor(boolean newObj, Object self, Object value) {
        var flag = JSType.toBoolean(value);

        if (newObj) {
            return new NativeBoolean(flag);
        }

        return flag;
    }

    private static Boolean getBoolean(Object self) {
        if (self instanceof Boolean) {
            return ((Boolean)self);
        } else if (self instanceof NativeBoolean) {
            return ((NativeBoolean)self).getValue();
        } else if (self != null && self == Global.instance().getBooleanPrototype()) {
            return false;
        } else {
            throw typeError("not.a.boolean", ScriptRuntime.safeToString(self));
        }
    }

    /**
     * Lookup the appropriate method for an invoke dynamic call.
     */
    public static GuardedInvocation lookupPrimitive(LinkRequest request, Object receiver) {
        return PrimitiveLookup.lookupPrimitive(request, Boolean.class, new NativeBoolean((Boolean)receiver), WRAPFILTER, PROTOFILTER);
    }

    /**
     * Wrap a native boolean in a NativeBoolean object.
     */
    @SuppressWarnings("unused")
    private static NativeBoolean wrapFilter(Object receiver) {
        return new NativeBoolean((Boolean)receiver);
    }

    @SuppressWarnings("unused")
    private static Object protoFilter(Object object) {
        return Global.instance().getBooleanPrototype();
    }

    private static MethodHandle findOwnMH(String name, MethodType type) {
        return MH.findStatic(MethodHandles.lookup(), NativeBoolean.class, name, type);
    }

}
