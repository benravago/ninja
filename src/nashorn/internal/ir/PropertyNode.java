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

package nashorn.internal.ir;

import nashorn.internal.ir.annotations.Immutable;
import nashorn.internal.ir.visitor.NodeVisitor;

/**
 * IR representation of an object literal property.
 */
@Immutable
public final class PropertyNode extends Node {

    /** Property key. */
    private final Expression key;

    /** Property value. */
    private final Expression value;

    /** Property getter. */
    private final FunctionNode getter;

    /** Property getter. */
    private final FunctionNode setter;

    /** static property flag */
    private final boolean isStatic;

    /** Computed property flag */
    private final boolean computed;

    /**
     * Constructor
     */
    public PropertyNode(long token, int finish, Expression key, Expression value, FunctionNode getter, FunctionNode setter, boolean isStatic, boolean computed) {
        super(token, finish);
        this.key = key;
        this.value = value;
        this.getter = getter;
        this.setter = setter;
        this.isStatic = isStatic;
        this.computed = computed;
    }

    private PropertyNode(PropertyNode propertyNode, Expression key, Expression value, FunctionNode getter, FunctionNode setter, boolean isStatic, boolean computed) {
        super(propertyNode);
        this.key = key;
        this.value = value;
        this.getter = getter;
        this.setter = setter;
        this.isStatic = isStatic;
        this.computed = computed;
    }

    /**
     * Get the name of the property key, or {@code null} if key is a computed name.
     */
    public String getKeyName() {
        return !computed && key instanceof PropertyKey ? ((PropertyKey) key).getPropertyName() : null;
    }

    @Override
    public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterPropertyNode(this)) {
            return visitor.leavePropertyNode(
                setKey((Expression) key.accept(visitor)).
                setValue(value == null ? null : (Expression)value.accept(visitor)).
                setGetter(getter == null ? null : (FunctionNode)getter.accept(visitor)).
                setSetter(setter == null ? null : (FunctionNode)setter.accept(visitor)));
        }

        return this;
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        if (value instanceof FunctionNode && ((FunctionNode)value).getIdent() != null) {
            value.toString(sb);
        }

        if (value != null) {
            ((Node)key).toString(sb, printType);
            sb.append(": ");
            value.toString(sb, printType);
        }

        if (getter != null) {
            sb.append(' ');
            getter.toString(sb, printType);
        }

        if (setter != null) {
            sb.append(' ');
            setter.toString(sb, printType);
        }
    }

    /**
     * Get the getter for this property
     */
    public FunctionNode getGetter() {
        return getter;
    }

    /**
     * Set the getter of this property, null if none
     */
    public PropertyNode setGetter(FunctionNode getter) {
        if (this.getter == getter) {
            return this;
        }
        return new PropertyNode(this, key, value, getter, setter, isStatic, computed);
    }

    /**
     * Return the key for this property node
     */
    public Expression getKey() {
        return key;
    }

    private PropertyNode setKey(Expression key) {
        if (this.key == key) {
            return this;
        }
        return new PropertyNode(this, key, value, getter, setter, isStatic, computed);
    }

    /**
     * Get the setter for this property
     */
    public FunctionNode getSetter() {
        return setter;
    }

    /**
     * Set the setter for this property, null if none
     */
    public PropertyNode setSetter(FunctionNode setter) {
        if (this.setter == setter) {
            return this;
        }
        return new PropertyNode(this, key, value, getter, setter, isStatic, computed);
    }

    /**
     * Get the value of this property
     */
    public Expression getValue() {
        return value;
    }

    /**
     * Set the value of this property
     */
    public PropertyNode setValue(Expression value) {
        if (this.value == value) {
            return this;
        }
        return new PropertyNode(this, key, value, getter, setter, isStatic, computed);
    }

    /**
     * Returns true if this is a static property.
     */
    public boolean isStatic() {
        return isStatic;
    }

    /**
     * Returns true if this is a computed property.
     */
    public boolean isComputed() {
        return computed;
    }

}
