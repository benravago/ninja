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

import static nashorn.internal.lookup.Lookup.MH;
import static nashorn.internal.runtime.ECMAErrors.rangeError;
import static nashorn.internal.runtime.ECMAErrors.typeError;
import static nashorn.internal.runtime.ScriptRuntime.UNDEFINED;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import jdk.dynalink.linker.GuardedInvocation;
import jdk.dynalink.linker.LinkRequest;
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
import nashorn.internal.runtime.linker.NashornGuards;
import nashorn.internal.runtime.linker.PrimitiveLookup;

/**
 * ECMA 15.7 Number Objects.
 *
 */
@ScriptClass("Number")
public final class NativeNumber extends ScriptObject {

    /** Method handle to create an object wrapper for a primitive number. */
    static final MethodHandle WRAPFILTER = findOwnMH("wrapFilter", MH.type(NativeNumber.class, Object.class));
    /** Method handle to retrieve the Number prototype object. */
    private static final MethodHandle PROTOFILTER = findOwnMH("protoFilter", MH.type(Object.class, Object.class));

    /** ECMA 15.7.3.2 largest positive finite value */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT, where = Where.CONSTRUCTOR)
    public static final double MAX_VALUE = Double.MAX_VALUE;

    /** ECMA 15.7.3.3 smallest positive finite value */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT, where = Where.CONSTRUCTOR)
    public static final double MIN_VALUE = Double.MIN_VALUE;

    /** ECMA 15.7.3.4 NaN */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT, where = Where.CONSTRUCTOR)
    public static final double NaN = Double.NaN;

    /** ECMA 15.7.3.5 negative infinity */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT, where = Where.CONSTRUCTOR)
    public static final double NEGATIVE_INFINITY = Double.NEGATIVE_INFINITY;

    /** ECMA 15.7.3.5 positive infinity */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT, where = Where.CONSTRUCTOR)
    public static final double POSITIVE_INFINITY = Double.POSITIVE_INFINITY;

    private final double  value;

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private NativeNumber(final double value, final ScriptObject proto, final PropertyMap map) {
        super(proto, map);
        this.value = value;
    }

    NativeNumber(final double value, final Global global) {
        this(value, global.getNumberPrototype(), $nasgenmap$);
    }

    private NativeNumber(final double value) {
        this(value, Global.instance());
    }


    @Override
    public String safeToString() {
        return "[Number " + toString() + "]";
    }

    @Override
    public String toString() {
        return Double.toString(getValue());
    }

    /**
     * Get the value of this Number
     * @return a {@code double} representing the Number value
     */
    public double getValue() {
        return doubleValue();
    }

    /**
     * Get the value of this Number
     * @return a {@code double} representing the Number value
     */
    public double doubleValue() {
        return value;
    }

    @Override
    public String getClassName() {
        return "Number";
    }

    /**
     * ECMA 15.7.2 - The Number constructor
     *
     * @param newObj is this Number instantiated with the new operator
     * @param self   self reference
     * @param args   value of number
     * @return the Number instance (internally represented as a {@code NativeNumber})
     */
    @Constructor(arity = 1)
    public static Object constructor(final boolean newObj, final Object self, final Object... args) {
        final double num = (args.length > 0) ? JSType.toNumber(args[0]) : 0.0;

        return newObj? new NativeNumber(num) : num;
    }

    /**
     * ECMA 15.7.4.2 Number.prototype.toString ( [ radix ] )
     *
     * @param self  self reference
     * @param radix radix to use for string conversion
     * @return string representation of this Number in the given radix
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static String toString(final Object self, final Object radix) {
        if (radix != UNDEFINED) {
            final int intRadix = JSType.toInteger(radix);
            if (intRadix != 10) {
                if (intRadix < 2 || intRadix > 36) {
                    throw rangeError("invalid.radix");
                }
                return JSType.toString(getNumberValue(self), intRadix);
            }
        }

        return JSType.toString(getNumberValue(self));
    }

    /**
     * ECMA 15.7.4.3 Number.prototype.toLocaleString()
     *
     * @param self self reference
     * @return localized string for this Number
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static String toLocaleString(final Object self) {
        return JSType.toString(getNumberValue(self));
    }


    /**
     * ECMA 15.7.4.4 Number.prototype.valueOf ( )
     *
     * @param self self reference
     * @return number value for this Number
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static double valueOf(final Object self) {
        return getNumberValue(self);
    }

    /**
     * Lookup the appropriate method for an invoke dynamic call.
     * @param request  The link request
     * @param receiver receiver of call
     * @return Link to be invoked at call site.
     */
    public static GuardedInvocation lookupPrimitive(final LinkRequest request, final Object receiver) {
        return PrimitiveLookup.lookupPrimitive(request, NashornGuards.getNumberGuard(), new NativeNumber(((Number)receiver).doubleValue()), WRAPFILTER, PROTOFILTER);
    }

    @SuppressWarnings("unused")
    private static NativeNumber wrapFilter(final Object receiver) {
        return new NativeNumber(((Number)receiver).doubleValue());
    }

    @SuppressWarnings("unused")
    private static Object protoFilter(final Object object) {
        return Global.instance().getNumberPrototype();
    }

    private static double getNumberValue(final Object self) {
        if (self instanceof Number) {
            return ((Number)self).doubleValue();
        } else if (self instanceof NativeNumber) {
            return ((NativeNumber)self).getValue();
        } else if (self != null && self == Global.instance().getNumberPrototype()) {
            return 0.0;
        } else {
            throw typeError("not.a.number", ScriptRuntime.safeToString(self));
        }
    }

    private static MethodHandle findOwnMH(final String name, final MethodType type) {
        return MH.findStatic(MethodHandles.lookup(), NativeNumber.class, name, type);
    }
}
