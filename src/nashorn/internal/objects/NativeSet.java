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

import nashorn.internal.Util;
import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.Getter;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.objects.annotations.Where;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.Undefined;
import nashorn.internal.runtime.linker.Bootstrap;
import static nashorn.internal.objects.NativeMap.convertKey;
import static nashorn.internal.runtime.ECMAErrors.typeError;

/**
 * This implements the ECMA6 Set object.
 */
@ScriptClass("Set")
public class NativeSet extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    // our set/map implementation
    private final LinkedMap map = new LinkedMap();

    // Invoker for the forEach callback
    private final static Object FOREACH_INVOKER_KEY = new Object();

    private NativeSet(ScriptObject proto, PropertyMap map) {
        super(proto, map);
    }

    /**
     * ECMA6 23.1 Set constructor
     */
    @Constructor(arity = 0)
    public static Object construct(boolean isNew, Object self, Object arg){
        if (!isNew) {
            throw typeError("constructor.requires.new", "Set");
        }
        var global = Global.instance();
        var set = new NativeSet(global.getSetPrototype(), $nasgenmap$);
        populateSet(set.getJavaMap(), arg, global);
        return set;
    }

    /**
     * ECMA6 23.2.3.1 Set.prototype.add ( value )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object add(Object self, Object value) {
        getNativeSet(self).map.set(convertKey(value), null);
        return self;
    }

    /**
     * ECMA6 23.2.3.7 Set.prototype.has ( value )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static boolean has(Object self, Object value) {
        return getNativeSet(self).map.has(convertKey(value));
    }

    /**
     * ECMA6 23.2.3.2 Set.prototype.clear ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static void clear(Object self) {
        getNativeSet(self).map.clear();
    }

    /**
     * ECMA6 23.2.3.4 Set.prototype.delete ( value )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static boolean delete(Object self, Object value) {
        return getNativeSet(self).map.delete(convertKey(value));
    }

    /**
     * ECMA6 23.2.3.9 get Set.prototype.size
     */
    @Getter(attributes = Attribute.NOT_ENUMERABLE | Attribute.IS_ACCESSOR, where = Where.PROTOTYPE)
    public static int size(Object self) {
        return getNativeSet(self).map.size();
    }

    /**
     * ECMA6 23.2.3.5 Set.prototype.entries ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object entries(Object self) {
        return new SetIterator(getNativeSet(self), AbstractIterator.IterationKind.KEY_VALUE, Global.instance());
    }

    /**
     * ECMA6 23.2.3.8 Set.prototype.keys ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object keys(Object self) {
        return new SetIterator(getNativeSet(self), AbstractIterator.IterationKind.KEY, Global.instance());
    }

    /**
     * ECMA6 23.2.3.10 Set.prototype.values ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object values(Object self) {
        return new SetIterator(getNativeSet(self), AbstractIterator.IterationKind.VALUE, Global.instance());
    }

    /**
     * ECMA6 23.2.3.11 Set.prototype [ @@iterator ] ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, name = "@@iterator")
    public static Object getIterator(Object self) {
        return new SetIterator(getNativeSet(self), AbstractIterator.IterationKind.VALUE, Global.instance());
    }

    /**
     * ECMA6 23.2.3.6 Set.prototype.forEach ( callbackfn [ , thisArg ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static void forEach(Object self, Object callbackFn, Object thisArg) {
        var set = getNativeSet(self);
        if (!Bootstrap.isCallable(callbackFn)) {
            throw typeError("not.a.function", ScriptRuntime.safeToString(callbackFn));
        }
        var invoker = Global.instance().getDynamicInvoker(FOREACH_INVOKER_KEY,
            () -> Bootstrap.createDynamicCallInvoker(Object.class, Object.class, Object.class, Object.class, Object.class, Object.class));

        var iterator = set.getJavaMap().getIterator();
        for (;;) {
            var node = iterator.next();
            if (node == null) {
                break;
            }

            try {
                var result = invoker.invokeExact(callbackFn, thisArg, node.getKey(), node.getKey(), self);
            } catch (Throwable t) {
                Util.uncheck(t);
            }
        }
    }

    @Override
    public String getClassName() {
        return "Set";
    }

    static void populateSet(LinkedMap map, Object arg, Global global) {
        if (arg != null && arg != Undefined.getUndefined()) {
            AbstractIterator.iterate(arg, global, value -> map.set(convertKey(value), null));
        }
    }

    LinkedMap getJavaMap() {
        return map;
    }

    private static NativeSet getNativeSet(Object self) {
        if (self instanceof NativeSet) {
            return (NativeSet) self;
        } else {
            throw typeError("not.a.set", ScriptRuntime.safeToString(self));
        }
    }

}
