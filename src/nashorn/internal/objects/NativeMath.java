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

import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.Property;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.objects.annotations.SpecializedFunction;
import nashorn.internal.objects.annotations.Where;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;

/**
 * ECMA 15.8 The Math Object
 */
@ScriptClass("Math")
public final class NativeMath extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private NativeMath() {
        // don't create me!
        throw new UnsupportedOperationException();
    }

    /** ECMA 15.8.1.1 - E, always a double constant. Not writable or configurable */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT, where = Where.CONSTRUCTOR)
    public static final double E = Math.E;

    /** ECMA 15.8.1.2 - LN10, always a double constant. Not writable or configurable */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT, where = Where.CONSTRUCTOR)
    public static final double LN10 = 2.302585092994046;

    /** ECMA 15.8.1.3 - LN2, always a double constant. Not writable or configurable */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT, where = Where.CONSTRUCTOR)
    public static final double LN2 = 0.6931471805599453;

    /** ECMA 15.8.1.4 - LOG2E, always a double constant. Not writable or configurable */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT, where = Where.CONSTRUCTOR)
    public static final double LOG2E = 1.4426950408889634;

    /** ECMA 15.8.1.5 - LOG10E, always a double constant. Not writable or configurable */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT, where = Where.CONSTRUCTOR)
    public static final double LOG10E = 0.4342944819032518;

    /** ECMA 15.8.1.6 - PI, always a double constant. Not writable or configurable */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT, where = Where.CONSTRUCTOR)
    public static final double PI = Math.PI;

    /** ECMA 15.8.1.7 - SQRT1_2, always a double constant. Not writable or configurable */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT, where = Where.CONSTRUCTOR)
    public static final double SQRT1_2 = 0.7071067811865476;

    /** ECMA 15.8.1.8 - SQRT2, always a double constant. Not writable or configurable */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT, where = Where.CONSTRUCTOR)
    public static final double SQRT2 = 1.4142135623730951;

    /**
     * ECMA 15.8.2.1 abs(x)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double abs(Object self, Object x) {
        return Math.abs(JSType.toNumber(x));
    }

    /**
     * ECMA 15.8.2.1 abs(x) - specialization for int values
     */
    @SpecializedFunction
    public static double abs(Object self, int x) {
        return x == Integer.MIN_VALUE? Math.abs((double)x) : Math.abs(x);
    }

    /**
     * ECMA 15.8.2.1 abs(x) - specialization for long values
     */
    @SpecializedFunction
    public static long abs(Object self, long x) {
        return Math.abs(x);
    }

    /**
     * ECMA 15.8.2.1 abs(x) - specialization for double values
     */
    @SpecializedFunction
    public static double abs(Object self, double x) {
        return Math.abs(x);
    }

    /**
     * ECMA 15.8.2.2 acos(x)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double acos(Object self, Object x) {
        return Math.acos(JSType.toNumber(x));
    }

    /**
     * ECMA 15.8.2.2 acos(x) - specialization for double values
     */
    @SpecializedFunction
    public static double acos(Object self, double x) {
        return Math.acos(x);
    }

    /**
     * ECMA 15.8.2.3 asin(x)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double asin(Object self, Object x) {
        return Math.asin(JSType.toNumber(x));
    }

    /**
     * ECMA 15.8.2.3 asin(x) - specialization for double values
     */
    @SpecializedFunction
    public static double asin(Object self, double x) {
        return Math.asin(x);
    }

    /**
     * ECMA 15.8.2.4 atan(x)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double atan(Object self, Object x) {
        return Math.atan(JSType.toNumber(x));
    }

    /**
     * ECMA 15.8.2.4 atan(x) - specialization for double values
     */
    @SpecializedFunction
    public static double atan(Object self, double x) {
        return Math.atan(x);
    }

    /**
     * ECMA 15.8.2.5 atan2(x,y)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double atan2(Object self, Object y, Object x) {
        return Math.atan2(JSType.toNumber(y), JSType.toNumber(x));
    }

    /**
     * ECMA 15.8.2.5 atan2(x,y) - specialization for double values
     */
    @SpecializedFunction
    public static double atan2(Object self, double y, double x) {
        return Math.atan2(y,x);
    }

    /**
     * ECMA 15.8.2.6 ceil(x)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double ceil(Object self, Object x) {
        return Math.ceil(JSType.toNumber(x));
    }

    /**
     * ECMA 15.8.2.6 ceil(x) - specialized version for ints
     */
    @SpecializedFunction
    public static int ceil(Object self, int x) {
        return x;
    }

    /**
     * ECMA 15.8.2.6 ceil(x) - specialized version for longs
     */
    @SpecializedFunction
    public static long ceil(Object self, long x) {
        return x;
    }

    /**
     * ECMA 15.8.2.6 ceil(x) - specialized version for doubles
     */
    @SpecializedFunction
    public static double ceil(Object self, double x) {
        return Math.ceil(x);
    }

    /**
     * ECMA 15.8.2.7 cos(x)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double cos(Object self, Object x) {
        return Math.cos(JSType.toNumber(x));
    }

    /**
     * ECMA 15.8.2.7 cos(x) - specialized version for doubles
     */
    @SpecializedFunction
    public static double cos(Object self, double x) {
        return Math.cos(x);
    }

    /**
     * ECMA 15.8.2.8 exp(x)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double exp(Object self, Object x) {
        return Math.exp(JSType.toNumber(x));
    }

    /**
     * ECMA 15.8.2.9 floor(x)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double floor(Object self, Object x) {
        return Math.floor(JSType.toNumber(x));
    }

    /**
     * ECMA 15.8.2.9 floor(x) - specialized version for ints
     */
    @SpecializedFunction
    public static int floor(Object self, int x) {
        return x;
    }

    /**
     * ECMA 15.8.2.9 floor(x) - specialized version for longs
     */
    @SpecializedFunction
    public static long floor(Object self, long x) {
        return x;
    }

    /**
     * ECMA 15.8.2.9 floor(x) - specialized version for doubles
     */
    @SpecializedFunction
    public static double floor(Object self, double x) {
        return Math.floor(x);
    }

    /**
     * ECMA 15.8.2.10 log(x)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double log(Object self, Object x) {
        return Math.log(JSType.toNumber(x));
    }

    /**
     * ECMA 15.8.2.10 log(x) - specialized version for doubles
     */
    @SpecializedFunction
    public static double log(Object self, double x) {
        return Math.log(x);
    }

    /**
     * ECMA 15.8.2.11 max(x)
     */
    @Function(arity = 2, attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double max(Object self, Object... args) {
        switch (args.length) {
            case 0 -> {
                return Double.NEGATIVE_INFINITY;
            }
            case 1 -> {
                return JSType.toNumber(args[0]);
            }
            default -> {
                var res = JSType.toNumber(args[0]);
                for (var i = 1; i < args.length; i++) {
                    res = Math.max(res, JSType.toNumber(args[i]));
                }
                return res;
            }
        }
    }

    /**
     * ECMA 15.8.2.11 max(x) - specialized no args version
     */
    @SpecializedFunction
    public static double max(Object self) {
        return Double.NEGATIVE_INFINITY;
    }

    /**
     * ECMA 15.8.2.11 max(x) - specialized version for ints
     */
    @SpecializedFunction
    public static int max(Object self, int x, int y) {
        return Math.max(x, y);
    }

    /**
     * ECMA 15.8.2.11 max(x) - specialized version for longs
     */
    @SpecializedFunction
    public static long max(Object self, long x, long y) {
        return Math.max(x, y);
    }

    /**
     * ECMA 15.8.2.11 max(x) - specialized version for doubles
     */
    @SpecializedFunction
    public static double max(Object self, double x, double y) {
        return Math.max(x, y);
    }

    /**
     * ECMA 15.8.2.11 max(x) - specialized version for two Object args
     */
    @SpecializedFunction
    public static double max(Object self, Object x, Object y) {
        return Math.max(JSType.toNumber(x), JSType.toNumber(y));
    }

    /**
     * ECMA 15.8.2.12 min(x)
     */
    @Function(arity = 2, attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double min(Object self, Object... args) {
        switch (args.length) {
            case 0 -> {
                return Double.POSITIVE_INFINITY;
                }
            case 1 -> {
                return JSType.toNumber(args[0]);
                }
            default -> {
                var res = JSType.toNumber(args[0]);
                for (var i = 1; i < args.length; i++) {
                    res = Math.min(res, JSType.toNumber(args[i]));
                }
                return res;
            }
        }
    }

    /**
     * ECMA 15.8.2.11 min(x) - specialized no args version
     */
    @SpecializedFunction
    public static double min(Object self) {
        return Double.POSITIVE_INFINITY;
    }

    /**
     * ECMA 15.8.2.12 min(x) - specialized version for ints
     */
    @SpecializedFunction
    public static int min(Object self, int x, int y) {
        return Math.min(x, y);
    }

    /**
     * ECMA 15.8.2.12 min(x) - specialized version for longs
     */
    @SpecializedFunction
    public static long min(Object self, long x, long y) {
        return Math.min(x, y);
    }

    /**
     * ECMA 15.8.2.12 min(x) - specialized version for doubles
     */
    @SpecializedFunction
    public static double min(Object self, double x, double y) {
        return Math.min(x, y);
    }

    /**
     * ECMA 15.8.2.12 min(x) - specialized version for two Object args
     */
    @SpecializedFunction
    public static double min(Object self, Object x, Object y) {
        return Math.min(JSType.toNumber(x), JSType.toNumber(y));
    }

    /**
     * ECMA 15.8.2.13 pow(x,y)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double pow(Object self, Object x, Object y) {
        return Math.pow(JSType.toNumber(x), JSType.toNumber(y));
    }

    /**
     * ECMA 15.8.2.13 pow(x,y) - specialized version for doubles
     */
    @SpecializedFunction
    public static double pow(Object self, double x, double y) {
        return Math.pow(x, y);
    }

    /**
     * ECMA 15.8.2.14 random()
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double random(Object self) {
        return Math.random();
    }

    /**
     * ECMA 15.8.2.15 round(x)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double round(Object self, Object x) {
        var d = JSType.toNumber(x);
        if (Math.getExponent(d) >= 52) {
            return d;
        }
        return Math.copySign(Math.floor(d + 0.5), d);
    }

    /**
     * ECMA 15.8.2.16 sin(x)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double sin(Object self, Object x) {
        return Math.sin(JSType.toNumber(x));
    }

    /**
     * ECMA 15.8.2.16 sin(x) - specialized version for doubles
     */
    @SpecializedFunction
    public static double sin(Object self, double x) {
        return Math.sin(x);
    }

    /**
     * ECMA 15.8.2.17 sqrt(x)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static double sqrt(Object self, Object x) {
        return Math.sqrt(JSType.toNumber(x));
    }

    /**
     * ECMA 15.8.2.17 sqrt(x) - specialized version for doubles
     */
    @SpecializedFunction
    public static double sqrt(Object self, double x) {
        return Math.sqrt(x);
    }

    /**
     * ECMA 15.8.2.18 tan(x)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where=Where.CONSTRUCTOR)
    public static double tan(Object self, Object x) {
        return Math.tan(JSType.toNumber(x));
    }

    /**
     * ECMA 15.8.2.18 tan(x) - specialized version for doubles
     */
    @SpecializedFunction
    public static double tan(Object self, double x) {
        return Math.tan(x);
    }

}
