/*
 * Copyright (c) 2010, 2016, Oracle and/or its affiliates. All rights reserved.
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

import nashorn.internal.codegen.types.Type;
import nashorn.internal.ir.annotations.Immutable;
import nashorn.internal.ir.visitor.NodeVisitor;

/**
 * IR representation of a property access (period operator.)
 */
@Immutable
public final class AccessNode extends BaseNode {

    /** Property name. */
    private final String property;

    /**
     * Constructor
     */
    public AccessNode(long token, int finish, Expression base, String property) {
        super(token, finish, base, false, false);
        this.property = property;
    }

    private AccessNode(AccessNode accessNode, Expression base, String property, boolean isFunction, Type type, int id, boolean isSuper) {
        super(accessNode, base, isFunction, type, id, isSuper);
        this.property = property;
    }

    /**
     * Assist in IR navigation.
     */
    @Override
    public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterAccessNode(this)) {
            return visitor.leaveAccessNode(
                setBase((Expression)base.accept(visitor)));
        }
        return this;
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        var needsParen = tokenType().needsParens(getBase().tokenType(), true);

        if (printType) {
            optimisticTypeToString(sb);
        }

        if (needsParen) {
            sb.append('(');
        }

        base.toString(sb, printType);

        if (needsParen) {
            sb.append(')');
        }
        sb.append('.');

        sb.append(property);
    }

    /**
     * Get the property name
     */
    public String getProperty() {
        return property;
    }

    private AccessNode setBase(Expression base) {
        if (this.base == base) {
            return this;
        }
        return new AccessNode(this, base, property, isFunction(), type, programPoint, isSuper());
    }

    @Override
    public AccessNode setType(Type type) {
        if (this.type == type) {
            return this;
        }
        return new AccessNode(this, base, property, isFunction(), type, programPoint, isSuper());
    }

    @Override
    public AccessNode setProgramPoint(int programPoint) {
        if (this.programPoint == programPoint) {
            return this;
        }
        return new AccessNode(this, base, property, isFunction(), type, programPoint, isSuper());
    }

    @Override
    public AccessNode setIsFunction() {
        if (isFunction()) {
            return this;
        }
        return new AccessNode(this, base, property, true, type, programPoint, isSuper());
    }

    @Override
    public AccessNode setIsSuper() {
        if (isSuper()) {
            return this;
        }
        return new AccessNode(this, base, property, isFunction(), type, programPoint, true);
    }

}
