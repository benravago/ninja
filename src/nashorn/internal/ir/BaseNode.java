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

import static nashorn.internal.runtime.UnwarrantedOptimismException.INVALID_PROGRAM_POINT;

import nashorn.internal.codegen.types.Type;
import nashorn.internal.ir.annotations.Immutable;
import nashorn.internal.parser.TokenType;

/**
 * IR base for accessing/indexing nodes.
 *
 * @see AccessNode
 * @see IndexNode
 */
@Immutable
public abstract class BaseNode extends Expression implements FunctionCall, Optimistic {

    /** Base Node. */
    protected final Expression base;

    private final boolean isFunction;

    /** Callsite type for this node, if overridden optimistically or conservatively depending on coercion */
    protected final Type type;

    /** Program point id */
    protected final int programPoint;

    /** Super property access. */
    private final boolean isSuper;

    /**
     * Constructor
     */
    public BaseNode(long token, int finish, Expression base, boolean isFunction, boolean isSuper) {
        super(token, base.getStart(), finish);
        this.base = base;
        this.isFunction = isFunction;
        this.type = null;
        this.programPoint = INVALID_PROGRAM_POINT;
        this.isSuper = isSuper;
    }

    /**
     * Copy constructor for immutable nodes
     */
    protected BaseNode(BaseNode baseNode, Expression base, boolean isFunction, Type callSiteType, int programPoint, boolean isSuper) {
        super(baseNode);
        this.base = base;
        this.isFunction = isFunction;
        this.type = callSiteType;
        this.programPoint = programPoint;
        this.isSuper = isSuper;
    }

    /**
     * Get the base node for this access
     */
    public Expression getBase() {
        return base;
    }

    @Override
    public boolean isFunction() {
        return isFunction;
    }

    @Override
    public Type getType() {
        return type == null ? getMostPessimisticType() : type;
    }

    @Override
    public int getProgramPoint() {
        return programPoint;
    }

    @Override
    public Type getMostOptimisticType() {
        return Type.INT;
    }

    @Override
    public Type getMostPessimisticType() {
        return Type.OBJECT;
    }

    @Override
    public boolean canBeOptimistic() {
        return true;
    }

    /**
     * Return true if this node represents an index operation normally represented as {@link IndexNode}.
     */
    public boolean isIndex() {
        return isTokenType(TokenType.LBRACKET);
    }

    /**
     * Mark this node as being the callee operand of a {@link CallNode}.
     */
    public abstract BaseNode setIsFunction();

    /**
     * Returns {@code true} if a SuperProperty access.
     */
    public boolean isSuper() {
        return isSuper;
    }

    /**
     * Mark this node as being a SuperProperty access.
     */
    public abstract BaseNode setIsSuper();

}
