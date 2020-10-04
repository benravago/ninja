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

package nashorn.internal.runtime.arrays;

import nashorn.internal.runtime.ConsString;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.ScriptObject;

/**
 * Array index computation helpers that both throw exceptions or return invalid values.
 */
public final class ArrayIndex {
    private ArrayIndex() {}

    private static final int  INVALID_ARRAY_INDEX = -1;
    private static final long MAX_ARRAY_INDEX = 0xfffffffeL;

    /**
     * Fast conversion of non-negative integer string to long.
     * Returns long value of string or {@code -1} if string does not represent a valid index.
     */
    private static long fromString(String key) {
        long value = 0;
        var length = key.length();

        // Check for empty string or leading 0
        if (length == 0 || (length > 1 && key.charAt(0) == '0')) {
            return INVALID_ARRAY_INDEX;
        }

        // Fast toNumber.
        for (var i = 0; i < length; i++) {
            var digit = key.charAt(i);

            // If not a digit.
            if (digit < '0' || digit > '9') {
                return INVALID_ARRAY_INDEX;
            }

            // Insert digit.
            value = value * 10 + digit - '0';

            // Check for overflow (need to catch before wrap around.)
            if (value > MAX_ARRAY_INDEX) {
                return INVALID_ARRAY_INDEX;
            }
        }

        return value;
    }

    /**
     * Returns a valid array index in an int, if the object represents one, or {@code -1} if {@code key} does not represent a valid index.
     * This routine needs to perform quickly since all keys are tested with it.
     * <p>
     * The {@code key} parameter must be a JavaScript primitive type, i.e. one of {@code String}, {@code Number}, {@code Boolean}, {@code null}, or {@code undefined}.
     * {@code ScriptObject} instances should be converted to primitive with {@code String.class} hint before being passed to this method.
     * <p>
     * Note that negative return values other than {@code -1} are considered valid and can be converted to the actual index using {@link #toLongIndex(int)}.
     */
    public static int getArrayIndex(Object key) {
        if (key instanceof Integer) {
            return getArrayIndex(((Integer) key).intValue());
        } else if (key instanceof Double) {
            return getArrayIndex(((Double) key).doubleValue());
        } else if (key instanceof String) {
            return (int)fromString((String) key);
        } else if (key instanceof Long) {
            return getArrayIndex(((Long) key).longValue());
        } else if (key instanceof ConsString) {
            return (int)fromString(key.toString());
        }

        assert !(key instanceof ScriptObject);
        return INVALID_ARRAY_INDEX;
    }

    /**
     * Returns a valid array index in an int, if {@code key} represents one, or {@code -1} if {@code key} is not a valid array index.
     */
    public static int getArrayIndex(int key) {
        return (key >= 0) ? key : INVALID_ARRAY_INDEX;
    }

    /**
     * Returns a valid array index in an int, if the long represents one.
     */
    public static int getArrayIndex(long key) {
        if (key >= 0 && key <= MAX_ARRAY_INDEX) {
            return (int)key;
        }

        return INVALID_ARRAY_INDEX;
    }


    /**
     * Return a valid index for this double, if it represents one.
     * Doubles that aren't representable exactly as longs/ints aren't working array indexes, however, array[1.1] === array["1.1"] in JavaScript.
     */
    public static int getArrayIndex(double key) {
        if (JSType.isRepresentableAsInt(key)) {
            return getArrayIndex((int) key);
        } else if (JSType.isRepresentableAsLong(key)) {
            return getArrayIndex((long) key);
        }

        return INVALID_ARRAY_INDEX;
    }


    /**
     * Return a valid array index for this string, if it represents one.
     */
    public static int getArrayIndex(String key) {
        return (int)fromString(key);
    }

    /**
     * Check whether an index is valid as an array index.
     * This check only tests if it is the special "invalid array index" type, not if it is e.g. less than zero or corrupt in some other way
     */
    public static boolean isValidArrayIndex(int index) {
        return index != INVALID_ARRAY_INDEX;
    }

    /**
     * Convert an index to a long value.
     * This basically amounts to converting it into a {@link JSType#toUint32(int)} uint32} as the maximum array index in JavaScript is 0xfffffffe
     */
    public static long toLongIndex(int index) {
        return JSType.toUint32(index);
    }

    /**
     * Convert an index to a key string.
     * This is the same as calling {@link #toLongIndex(int)} and converting the result to String.
     */
    public static String toKey(int index) {
        return Long.toString(JSType.toUint32(index));
    }

}
