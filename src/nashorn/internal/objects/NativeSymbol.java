/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

import nashorn.internal.WeakValueCache;
import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.Property;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.objects.annotations.Where;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.Symbol;
import nashorn.internal.runtime.Undefined;
import nashorn.internal.runtime.linker.PrimitiveLookup;
import static nashorn.internal.lookup.Lookup.MH;
import static nashorn.internal.runtime.ECMAErrors.typeError;

/**
 * ECMAScript 6 - 19.4 Symbol Objects
 */
@ScriptClass("Symbol")
public final class NativeSymbol extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private final Symbol symbol;

    /** Method handle to create an object wrapper for a primitive symbol. */
    static final MethodHandle WRAPFILTER = findOwnMH("wrapFilter", MH.type(NativeSymbol.class, Object.class));

    /** Method handle to retrieve the Symbol prototype object. */
    private static final MethodHandle PROTOFILTER = findOwnMH("protoFilter", MH.type(Object.class, Object.class));

    /** See ES6 19.4.2.1 */
    private static WeakValueCache<String, Symbol> globalSymbolRegistry = new WeakValueCache<>();

    /**
     * ECMA 6 19.4.2.4 Symbol.iterator
     */
    @Property(where = Where.CONSTRUCTOR, attributes = Attribute.NON_ENUMERABLE_CONSTANT, name = "iterator")
    public static final Symbol iterator = new Symbol("Symbol.iterator");

    NativeSymbol(Symbol symbol) {
        this(symbol, Global.instance());
    }

    NativeSymbol(Symbol symbol, Global global) {
        this(symbol, global.getSymbolPrototype(), $nasgenmap$);
    }

    private NativeSymbol(Symbol symbol, ScriptObject prototype, PropertyMap map) {
        super(prototype, map);
        this.symbol = symbol;
    }

    private static Symbol getSymbolValue(Object self) {
        if (self instanceof Symbol) {
            return (Symbol) self;
        } else if (self instanceof NativeSymbol) {
            return ((NativeSymbol) self).symbol;
        } else {
            throw typeError("not.a.symbol", ScriptRuntime.safeToString(self));
        }
    }

    /**
     * Lookup the appropriate method for an invoke dynamic call.
     */
    public static GuardedInvocation lookupPrimitive(LinkRequest request, Object receiver) {
        return PrimitiveLookup.lookupPrimitive(request, Symbol.class, new NativeSymbol((Symbol)receiver), WRAPFILTER, PROTOFILTER);
    }

    // ECMA 6 19.4.3.4 Symbol.prototype [ @@toPrimitive ] ( hint )
    @Override
    public Object getDefaultValue(Class<?> typeHint) {
        // Just return the symbol value.
        return symbol;
    }

    /**
     * ECMA 6 19.4.3.2 Symbol.prototype.toString ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static String toString(Object self) {
        return getSymbolValue(self).toString();
    }


    /**
     * ECMA 6 19.4.3.3  Symbol.prototype.valueOf ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object valueOf(Object self) {
        return getSymbolValue(self);
    }

    /**
     * ECMA 6 19.4.1.1 Symbol ( [ description ] )
     */
    @Constructor(arity = 1)
    public static Object constructor(boolean newObj, Object self, Object... args) {
        if (newObj) {
            throw typeError("symbol.as.constructor");
        }
        var description = args.length > 0 && args[0] != Undefined.getUndefined() ? JSType.toString(args[0]) : "";
        return new Symbol(description);
    }

    /**
     * ES6 19.4.2.1 Symbol.for ( key )
     */
    @Function(name = "for", attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public synchronized static Object doFor(Object self, Object arg) {
        var name = JSType.toString(arg);
        return globalSymbolRegistry.getOrCreate(name, Symbol::new);
    }

    /**
     * ES6 19.4.2.5 Symbol.keyFor ( sym )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public synchronized static Object keyFor(Object self, Object arg) {
        if (!(arg instanceof Symbol)) {
            throw typeError("not.a.symbol", ScriptRuntime.safeToString(arg));
        }
        var name = ((Symbol) arg).getName();
        return globalSymbolRegistry.get(name) == arg ? name : Undefined.getUndefined();
    }

    @SuppressWarnings("unused")
    private static NativeSymbol wrapFilter(Object receiver) {
        return new NativeSymbol((Symbol)receiver);
    }

    @SuppressWarnings("unused")
    private static Object protoFilter(Object object) {
        return Global.instance().getSymbolPrototype();
    }

    private static MethodHandle findOwnMH(String name, MethodType type) {
        return MH.findStatic(MethodHandles.lookup(), NativeSymbol.class, name, type);
    }

}
