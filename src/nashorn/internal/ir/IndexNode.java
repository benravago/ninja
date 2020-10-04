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

import nashorn.internal.codegen.types.Type;
import nashorn.internal.ir.annotations.Immutable;
import nashorn.internal.ir.visitor.NodeVisitor;

/**
 * IR representation of an indexed access (brackets operator.)
 */
@Immutable
public final class IndexNode extends BaseNode {

    /** Property index. */
    private final Expression index;

    /**
     * Constructors
     */
    public IndexNode(long token, int finish, Expression base, Expression index) {
        super(token, finish, base, false, false);
        this.index = index;
    }

    private IndexNode(IndexNode indexNode, Expression base, Expression index, boolean isFunction, Type type, int programPoint, boolean isSuper) {
        super(indexNode, base, isFunction, type, programPoint, isSuper);
        this.index = index;
    }

    @Override
    public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterIndexNode(this)) {
            return visitor.leaveIndexNode(
                setBase((Expression)base.accept(visitor)).
                setIndex((Expression)index.accept(visitor)));
        }
        return this;
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        var needsParen = tokenType().needsParens(base.tokenType(), true);

        if (needsParen) {
            sb.append('(');
        }

        if (printType) {
            optimisticTypeToString(sb);
        }

        base.toString(sb, printType);

        if (needsParen) {
            sb.append(')');
        }

        sb.append('[');
        index.toString(sb, printType);
        sb.append(']');
    }

    /**
     * Get the index expression for this IndexNode
     */
    public Expression getIndex() {
        return index;
    }

    private IndexNode setBase(Expression base) {
        if (this.base == base) {
            return this;
        }
        return new IndexNode(this, base, index, isFunction(), type, programPoint, isSuper());
    }

    /**
     * Set the index expression for this node
     */
    public IndexNode setIndex(Expression index) {
        if (this.index == index) {
            return this;
        }
        return new IndexNode(this, base, index, isFunction(), type, programPoint, isSuper());
    }

    @Override
    public IndexNode setType(Type type) {
        if (this.type == type) {
            return this;
        }
        return new IndexNode(this, base, index, isFunction(), type, programPoint, isSuper());
    }

    @Override
    public IndexNode setIsFunction() {
        if (isFunction()) {
            return this;
        }
        return new IndexNode(this, base, index, true, type, programPoint, isSuper());
    }

    @Override
    public IndexNode setProgramPoint(int programPoint) {
        if (this.programPoint == programPoint) {
            return this;
        }
        return new IndexNode(this, base, index, isFunction(), type, programPoint, isSuper());
    }

    @Override
    public IndexNode setIsSuper() {
        if (isSuper()) {
            return this;
        }
        return new IndexNode(this, base, index, isFunction(), type, programPoint, true);
    }

}
