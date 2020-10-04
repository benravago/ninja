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
import java.util.Set;

/**
 * This interface can be implemented by an arbitrary Java class.
 *
 * Nashorn will treat objects of such classes just like nashorn script objects.
 * Usual nashorn operations like obj[i], obj.foo, obj.func(), delete obj.foo will be delegated to appropriate method call of this interface.
 *
 * @since 1.8u40
 */
public interface JSObject {

    /**
     * Call this object as a JavaScript function.
     * This is equivalent to 'func.apply(thiz, args)' in JavaScript.
     * @param thiz 'this' object to be passed to the function. This may be null.
     * @param args arguments to method
     * @return result of call
     */
    Object call(Object thiz, Object... args);

    /**
     * Call this 'constructor' JavaScript function to create a new object.
     * This is equivalent to 'new func(arg1, arg2...)' in JavaScript.
     * @param args arguments to method
     * @return result of constructor call
     */
    Object newObject(Object... args);

    /**
     * Evaluate a JavaScript expression.
     * @param s JavaScript expression to evaluate
     * @return evaluation result
     */
    Object eval(String s);

    /**
     * Retrieves a named member of this JavaScript object.
     * @param name of member
     * @return member
     * @throws NullPointerException if name is null
     */
    Object getMember(String name);

    /**
     * Retrieves an indexed member of this JavaScript object.
     * @param index index slot to retrieve
     * @return member
     */
    Object getSlot(int index);

    /**
     * Does this object have a named member?
     * @param name name of member
     * @return true if this object has a member of the given name
     */
    boolean hasMember(String name);

    /**
     * Does this object have a indexed property?
     * @param slot index to check
     * @return true if this object has a slot
     */
    boolean hasSlot(int slot);

    /**
     * Remove a named member from this JavaScript object
     * @param name name of the member
     * @throws NullPointerException if name is null
     */
    void removeMember(String name);

    /**
     * Set a named member in this JavaScript object
     * @param name  name of the member
     * @param value value of the member
     * @throws NullPointerException if name is null
     */
    void setMember(String name, Object value);

    /**
     * Set an indexed member in this JavaScript object
     * @param index index of the member slot
     * @param value value of the member
     */
    void setSlot(int index, Object value);

    // property and value iteration

    /**
     * Returns the set of all property names of this object.
     * @return set of property names
     */
    Set<String> keySet();

    /**
     * Returns the set of all property values of this object.
     * @return set of property values.
     */
    Collection<Object> values();

    // JavaScript instanceof check

    /**
     * Checking whether the given object is an instance of 'this' object.
     * @param instance instance to check
     * @return true if the given 'instance' is an instance of this 'function' object
     */
    boolean isInstance(Object instance);

    /**
     * Checking whether this object is an instance of the given 'clazz' object.
     * @param clazz clazz to check
     * @return true if this object is an instance of the given 'clazz'
     */
    boolean isInstanceOf(Object clazz);

    /**
     * ECMA [[Class]] property
     * @return ECMA [[Class]] property value of this object
     */
    String getClassName();

    /**
     * Is this a function object?
     * @return if this mirror wraps a ECMAScript function instance
     */
    boolean isFunction();

    /**
     * Is this an array object?
     * @return if this mirror wraps a ECMAScript array object
     */
    boolean isArray();

    /**
     * Implements this object's {@code [[DefaultValue]]} method as per ECMAScript 5.1 section 8.6.2.
     * @param hint the type hint. Should be either {@code null}, {@code Number.class} or {@code String.class}.
     * @return this object's default value.
     * @throws UnsupportedOperationException if the conversion can't be performed. The engine will convert this exception into a JavaScript {@code TypeError}.
     */
    default Object getDefaultValue(Class<?> hint) throws UnsupportedOperationException {
        return DefaultValueImpl.getDefaultValue(this, hint);
    }

}
