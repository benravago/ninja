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

package nashorn.internal.runtime;

/**
 * If your ScriptObject or similar PropertyAccess implementation only provides the most generic getters and setters and does nothing fancy with other, more primitive, types, then it is convenient to inherit this class and just fill out the methods left abstract
 */
public abstract class DefaultPropertyAccess implements PropertyAccess {

    @Override
    public int getInt(Object key, int programPoint) {
        return JSType.toInt32(get(key));
    }

    @Override
    public int getInt(double key, int programPoint) {
        return getInt(JSType.toObject(key), programPoint);
    }

    @Override
    public int getInt(int key, int programPoint) {
        return getInt(JSType.toObject(key), programPoint);
    }

    @Override
    public double getDouble(Object key, int programPoint) {
        return JSType.toNumber(get(key));
    }

    @Override
    public double getDouble(double key, int programPoint) {
        return getDouble(JSType.toObject(key), programPoint);
    }

    @Override
    public double getDouble(int key, int programPoint) {
        return getDouble(JSType.toObject(key), programPoint);
    }

    @Override
    public abstract Object get(Object key);

    @Override
    public Object get(double key) {
        return get(JSType.toObject(key));
    }

    @Override
    public Object get(int key) {
        return get(JSType.toObject(key));
    }

    @Override
    public void set(double key, int value, int flags) {
        set(JSType.toObject(key), JSType.toObject(value), flags);
    }

    @Override
    public void set(double key, double value, int flags) {
        set(JSType.toObject(key), JSType.toObject(value), flags);
    }

    @Override
    public void set(double key, Object value, int flags) {
        set(JSType.toObject(key), JSType.toObject(value), flags);
    }

    @Override
    public void set(int key, int value, int flags) {
        set(JSType.toObject(key), JSType.toObject(value), flags);
    }

    @Override
    public void set(int key, double value, int flags) {
        set(JSType.toObject(key), JSType.toObject(value), flags);
    }

    @Override
    public void set(int key, Object value, int flags) {
        set(JSType.toObject(key), value, flags);
    }

    @Override
    public void set(Object key, int value, int flags) {
        set(key, JSType.toObject(value), flags);
    }

    @Override
    public void set(Object key, double value, int flags) {
        set(key, JSType.toObject(value), flags);
    }

    @Override
    public abstract void set(Object key, Object value, int flags);

    @Override
    public abstract boolean has(Object key);

    @Override
    public boolean has(int key) {
        return has(JSType.toObject(key));
    }

    @Override
    public boolean has(double key) {
        return has(JSType.toObject(key));
    }

    @Override
    public boolean hasOwnProperty(int key) {
        return hasOwnProperty(JSType.toObject(key));
    }

    @Override
    public boolean hasOwnProperty(double key) {
        return hasOwnProperty(JSType.toObject(key));
    }

    @Override
    public abstract boolean hasOwnProperty(Object key);

    @Override
    public boolean delete(int key) {
        return delete(JSType.toObject(key));
    }

    @Override
    public boolean delete(double key) {
        return delete(JSType.toObject(key));
    }

}
