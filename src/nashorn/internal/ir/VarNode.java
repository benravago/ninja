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
 * Node represents a var/let declaration.
 */
@Immutable
public final class VarNode extends Statement implements Assignment<IdentNode> {

    /** Var name. */
    private final IdentNode name;

    /** Initialization expression. */
    private final Expression init;

    /** Is this a var statement (as opposed to a "var" in a for loop statement) */
    private final int flags;

    /**
     * Source order id to be used for this node.
     * If this is -1, then we the default which is start position of this node.
     * See also the method Node::getSourceOrder.
     */
    private final int sourceOrder;

    /** Flag for ES6 LET declaration */
    public static final int IS_LET                       = 1 << 0;

    /** Flag for ES6 CONST declaration */
    public static final int IS_CONST                     = 1 << 1;

    /**
     * Flag that determines if this is the last function declaration in a function.
     * This is used to micro optimize the placement of return value assignments for a program node
     */
    public static final int IS_LAST_FUNCTION_DECLARATION = 1 << 2;

    /**
     * Constructor
     */
    public VarNode(int lineNumber, long token, int finish, IdentNode name, Expression init) {
        this(lineNumber, token, finish, name, init, 0);
    }

    private VarNode(VarNode varNode, IdentNode name, Expression init, int flags) {
        super(varNode);
        this.sourceOrder = -1;
        this.name = init == null ? name : name.setIsInitializedHere();
        this.init = init;
        this.flags = flags;
    }

    /**
     * Constructor
     */
    public VarNode(int lineNumber, long token, int finish, IdentNode name, Expression init, int flags) {
        this(lineNumber, token, -1, finish, name, init, flags);
    }

    /**
     * Constructor
     */
    public VarNode(int lineNumber, long token, int sourceOrder, int finish, IdentNode name, Expression init, int flags) {
        super(lineNumber, token, finish);
        this.sourceOrder = sourceOrder;
        this.name = init == null ? name : name.setIsInitializedHere();
        this.init = init;
        this.flags = flags;
    }

    @Override
    public int getSourceOrder() {
        return sourceOrder == -1? super.getSourceOrder() : sourceOrder;
    }

    @Override
    public boolean isAssignment() {
        return hasInit();
    }

    @Override
    public IdentNode getAssignmentDest() {
        return isAssignment() ? name : null;
    }

    @Override
    public VarNode setAssignmentDest(IdentNode n) {
        return setName(n);
    }

    @Override
    public Expression getAssignmentSource() {
        return isAssignment() ? getInit() : null;
    }

    /**
     * Is this a VAR node block scoped? This returns true for ECMAScript 6 LET and CONST nodes.
     */
    public boolean isBlockScoped() {
        return getFlag(IS_LET) || getFlag(IS_CONST);
    }

    /**
     * Is this an ECMAScript 6 LET node?
     */
    public boolean isLet() {
        return getFlag(IS_LET);
    }

    /**
     * Is this an ECMAScript 6 CONST node?
     */
    public boolean isConst() {
        return getFlag(IS_CONST);
    }

    /**
     * Return the flags to use for symbols for this declaration.
     */
    public int getSymbolFlags() {
        if (isLet()) {
            return Symbol.IS_VAR | Symbol.IS_LET;
        } else if (isConst()) {
            return Symbol.IS_VAR | Symbol.IS_CONST;
        }
        return Symbol.IS_VAR;
    }

    /**
     * Does this variable declaration have an init value
     */
    public boolean hasInit() {
        return init != null;
    }

    /**
     * Assist in IR navigation.
     */
    @Override
    public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterVarNode(this)) {
            // var is right associative, so visit init before name
            var newInit = init == null ? null : (Expression)init.accept(visitor);
            var newName = (IdentNode)name.accept(visitor);
            VarNode newThis;
            if (name != newName || init != newInit) {
                newThis = new VarNode(this, newName, newInit, flags);
            } else {
                newThis = this;
            }
            return visitor.leaveVarNode(newThis);
        }
        return this;
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        sb.append(tokenType().getName())
          .append(' ');
        name.toString(sb, printType);

        if (init != null) {
            sb.append(" = ");
            init.toString(sb, printType);
        }
    }

    /**
     * If this is an assignment of the form {@code var x = init;}, get the init part.
     */
    public Expression getInit() {
        return init;
    }

    /**
     * Reset the initialization expression
     */
    public VarNode setInit(Expression init) {
        if (this.init == init) {
            return this;
        }
        return new VarNode(this, name, init, flags);
    }

    /**
     * Get the identifier for the variable
     */
    public IdentNode getName() {
        return name;
    }

    /**
     * Reset the identifier for this VarNode
     */
    public VarNode setName(IdentNode name) {
        if (this.name == name) {
            return this;
        }
        return new VarNode(this, name, init, flags);
    }

    private VarNode setFlags(int flags) {
        if (this.flags == flags) {
            return this;
        }
        return new VarNode(this, name, init, flags);
    }

    /**
     * Check if a flag is set for this var node
     */
    public boolean getFlag(int flag) {
        return (flags & flag) == flag;
    }

    /**
     * Set a flag for this var node
     */
    public VarNode setFlag(int flag) {
        return setFlags(flags | flag);
    }

    /**
     * Returns true if this is a function declaration.
     */
    public boolean isFunctionDeclaration() {
        return init instanceof FunctionNode && ((FunctionNode)init).isDeclared();
    }

}
