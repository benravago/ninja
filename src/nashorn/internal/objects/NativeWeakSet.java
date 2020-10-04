/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

import java.util.Map;
import java.util.WeakHashMap;

import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.Undefined;
import static nashorn.internal.objects.NativeWeakMap.checkKey;
import static nashorn.internal.runtime.ECMAErrors.typeError;
import static nashorn.internal.runtime.JSType.isPrimitive;

/**
 * This implements the ECMA6 WeakSet object.
 */
@ScriptClass("WeakSet")
public class NativeWeakSet extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private final Map<Object, Boolean> map = new WeakHashMap<>();

    private NativeWeakSet(ScriptObject proto, PropertyMap map) {
        super(proto, map);
    }

    /**
     * ECMA6 23.3.1 The WeakSet Constructor
     */
    @Constructor(arity = 0)
    public static Object construct(boolean isNew, Object self, Object arg) {
        if (!isNew) {
            throw typeError("constructor.requires.new", "WeakSet");
        }
        var  global = Global.instance();
        var weakSet = new NativeWeakSet(global.getWeakSetPrototype(), $nasgenmap$);
        populateWeakSet(weakSet.map, arg, global);
        return weakSet;
    }

    /**
     * ECMA6 23.4.3.1 WeakSet.prototype.add ( value )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object add(Object self, Object value) {
        var set = getSet(self);
        set.map.put(checkKey(value), Boolean.TRUE);
        return self;
    }

    /**
     * ECMA6 23.4.3.4 WeakSet.prototype.has ( value )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static boolean has(Object self, Object value) {
        var set = getSet(self);
        return !isPrimitive(value) && set.map.containsKey(value);
    }

    /**
     * ECMA6 23.4.3.3 WeakSet.prototype.delete ( value )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static boolean delete(Object self, Object value) {
        var map = getSet(self).map;
        if (isPrimitive(value)) {
            return false;
        }
        var returnValue = map.containsKey(value);
        map.remove(value);
        return returnValue;
    }

    @Override
    public String getClassName() {
        return "WeakSet";
    }

    static void populateWeakSet(Map<Object, Boolean> set, Object arg, Global global) {
        if (arg != null && arg != Undefined.getUndefined()) {
            AbstractIterator.iterate(arg, global, value -> {
                set.put(checkKey(value), Boolean.TRUE);
            });
        }
    }

    private static NativeWeakSet getSet(Object self) {
        if (self instanceof NativeWeakSet) {
            return (NativeWeakSet) self;
        } else {
            throw typeError("not.a.weak.set", ScriptRuntime.safeToString(self));
        }
    }

}
