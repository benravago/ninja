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

package nashorn.api.scripting;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * This is the base class for nashorn ScriptObjectMirror class.
 *
 * This class can also be subclassed by an arbitrary Java class.
 * Nashorn will treat objects of such classes just like nashorn script objects.
 * Usual nashorn operations like obj[i], obj.foo, obj.func(), delete obj.foo will be delegated to appropriate method call of this class.
 *
 * @since 1.8u40
 */
public abstract class AbstractJSObject implements JSObject {

    /**
     * The default constructor.
     */
    public AbstractJSObject() {}

    /**
     * This implementation always throws UnsupportedOperationException
     */
    @Override
    public Object call(Object thiz, Object... args) {
        throw new UnsupportedOperationException("call");
    }

    /**
     * This implementation always throws UnsupportedOperationException
     */
    @Override
    public Object newObject(Object... args) {
        throw new UnsupportedOperationException("newObject");
    }

    /**
     * This imlementation always throws UnsupportedOperationException
     */
    @Override
    public Object eval(String s) {
        throw new UnsupportedOperationException("eval");
    }

    /**
     * This implementation always returns null
     */
    @Override
    public Object getMember(String name) {
        Objects.requireNonNull(name);
        return null;
    }

    /**
     * This implementation always returns null
     */
    @Override
    public Object getSlot(int index) {
        return null;
    }

    /**
     * This implementation always returns false
     */
    @Override
    public boolean hasMember(String name) {
        Objects.requireNonNull(name);
        return false;
    }

    /**
     * This implementation always returns false
     */
    @Override
    public boolean hasSlot(int slot) {
        return false;
    }

    /**
     * This implementation is a no-op
     */
    @Override
    public void removeMember(String name) {
        Objects.requireNonNull(name);
        //empty
    }

    /**
     * This implementation is a no-op
     */
    @Override
    public void setMember(String name, Object value) {
        Objects.requireNonNull(name);
        //empty
    }

    /**
     * This implementation is a no-op
     */
    @Override
    public void setSlot(int index, Object value) {
        //empty
    }

    // property and value iteration

    /**
     * This implementation returns empty set
     */
    @Override
    public Set<String> keySet() {
        return Collections.emptySet();
    }

    /**
     * This implementation returns empty set
     */
    @Override
    public Collection<Object> values() {
        return Collections.emptySet();
    }

    // JavaScript instanceof check

    /**
     * This implementation always returns false
     */
    @Override
    public boolean isInstance(Object instance) {
        return false;
    }

    @Override
    public boolean isInstanceOf(Object clazz) {
        if (clazz instanceof JSObject) {
            return ((JSObject)clazz).isInstance(this);
        }

        return false;
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    /**
     * This implementation always returns false
     */
    @Override
    public boolean isFunction() {
        return false;
    }

    /**
     * This implementation always returns false
     */
    @Override
    public boolean isArray() {
        return false;
    }

}
