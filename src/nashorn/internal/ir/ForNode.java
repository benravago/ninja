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
 * IR representing a FOR statement.
 */
@Immutable
public final class ForNode extends LoopNode {

    /** Initialize expression for an ordinary for statement, or the LHS expression receiving iterated-over values in a for-in statement. */
    private final Expression init;

    /** Modify expression for an ordinary statement, or the source of the iterator in the for-in statement. */
    private final JoinPredecessorExpression modify;

    /** Iterator symbol. */
    private final Symbol iterator;

    /** Is this a normal for in loop? */
    public static final int IS_FOR_IN           = 1 << 0;

    /** Is this a normal for each in loop? */
    public static final int IS_FOR_EACH         = 1 << 1;

    /** Is this a ES6 for-of loop? */
    public static final int IS_FOR_OF           = 1 << 2;

    /** Does this loop need a per-iteration scope because its init contain a LET declaration? */
    public static final int PER_ITERATION_SCOPE = 1 << 3;

    private final int flags;

    /**
     * Constructs a ForNode
     */
    public ForNode(int lineNumber, long token, int finish, Block body, int flags){
        this(lineNumber, token, finish, body, flags, null, null, null);
    }

    /**
     * Constructor
     */
    public ForNode(int lineNumber, long token, int finish, Block body, int flags, Expression init, JoinPredecessorExpression test, JoinPredecessorExpression modify) {
        super(lineNumber, token, finish, body, test, false);
        this.flags  = flags;
        this.init = init;
        this.modify = modify;
        this.iterator = null;
    }

    private ForNode(ForNode forNode, Expression init, JoinPredecessorExpression test, Block body, JoinPredecessorExpression modify, int flags, boolean controlFlowEscapes, LocalVariableConversion conversion, Symbol iterator) {
        super(forNode, test, body, controlFlowEscapes, conversion);
        this.init   = init;
        this.modify = modify;
        this.flags  = flags;
        this.iterator = iterator;
    }

    @Override
    public Node ensureUniqueLabels(LexicalContext lc) {
        return Node.replaceInLexicalContext(lc, this, new ForNode(this, init, test, body, modify, flags, controlFlowEscapes, conversion, iterator));
    }

    @Override
    public Node accept(LexicalContext lc, NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterForNode(this)) {
            return visitor.leaveForNode(
                setInit(lc, init == null ? null : (Expression)init.accept(visitor)).
                setTest(lc, test == null ? null : (JoinPredecessorExpression)test.accept(visitor)).
                setModify(lc, modify == null ? null : (JoinPredecessorExpression)modify.accept(visitor)).
                setBody(lc, (Block)body.accept(visitor)));
        }
        return this;
    }

    @Override
    public void toString(StringBuilder sb, boolean printTypes) {
        sb.append("for");
        LocalVariableConversion.toString(conversion, sb).append(' ');

        if (isForIn()) {
            init.toString(sb, printTypes);
            sb.append(" in ");
            modify.toString(sb, printTypes);
        } else if (isForOf()) {
            init.toString(sb, printTypes);
            sb.append(" of ");
            modify.toString(sb, printTypes);
        } else {
            if (init != null) {
                init.toString(sb, printTypes);
            }
            sb.append("; ");
            if (test != null) {
                test.toString(sb, printTypes);
            }
            sb.append("; ");
            if (modify != null) {
                modify.toString(sb, printTypes);
            }
        }

        sb.append(')');
    }

    @Override
    public boolean hasGoto() {
        return !isForInOrOf() && test == null;
    }

    @Override
    public boolean mustEnter() {
        if (isForInOrOf()) {
            return false; // may be an empty set to iterate over, then we skip the loop
        }
        return test == null;
    }

    /**
     * Get the initialization expression for this for loop
     */
    public Expression getInit() {
        return init;
    }

    /**
     * Reset the initialization expression for this for loop
     */
    public ForNode setInit(LexicalContext lc, Expression init) {
        if (this.init == init) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new ForNode(this, init, test, body, modify, flags, controlFlowEscapes, conversion, iterator));
    }

    /**
     * Is this a for in construct rather than a standard init;condition;modification one
     */
    public boolean isForIn() {
        return (flags & IS_FOR_IN) != 0;
    }

    /**
     * Is this a for-of loop?
     */
    public boolean isForOf() {
        return (flags & IS_FOR_OF) != 0;
    }

    /**
     * Is this a for-in or for-of statement?
     */
    public boolean isForInOrOf() {
        return isForIn() || isForOf();
    }

    /**
     * Is this a for each construct, known from e.g. Rhino.
     * This will be a for of construct in ECMAScript 6
     */
    public boolean isForEach() {
        return (flags & IS_FOR_EACH) != 0;
    }

    /**
     * If this is a for in or for each construct, there is an iterator symbol
     */
    public Symbol getIterator() {
        return iterator;
    }

    /**
     * Assign an iterator symbol to this ForNode. Used for for in and for each constructs
     */
    public ForNode setIterator(LexicalContext lc, Symbol iterator) {
        if (this.iterator == iterator) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new ForNode(this, init, test, body, modify, flags, controlFlowEscapes, conversion, iterator));
    }

    /**
     * Get the modification expression for this ForNode
     */
    public JoinPredecessorExpression getModify() {
        return modify;
    }

    /**
     * Reset the modification expression for this ForNode
     */
    public ForNode setModify(LexicalContext lc, JoinPredecessorExpression modify) {
        if (this.modify == modify) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new ForNode(this, init, test, body, modify, flags, controlFlowEscapes, conversion, iterator));
    }

    @Override
    public ForNode setTest(LexicalContext lc, JoinPredecessorExpression test) {
        if (this.test == test) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new ForNode(this, init, test, body, modify, flags, controlFlowEscapes, conversion, iterator));
    }

    @Override
    public Block getBody() {
        return body;
    }

    @Override
    public ForNode setBody(LexicalContext lc, Block body) {
        if (this.body == body) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new ForNode(this, init, test, body, modify, flags, controlFlowEscapes, conversion, iterator));
    }

    @Override
    public ForNode setControlFlowEscapes(LexicalContext lc, boolean controlFlowEscapes) {
        if (this.controlFlowEscapes == controlFlowEscapes) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new ForNode(this, init, test, body, modify, flags, controlFlowEscapes, conversion, iterator));
    }

    @Override
    JoinPredecessor setLocalVariableConversionChanged(LexicalContext lc, LocalVariableConversion conversion) {
        return Node.replaceInLexicalContext(lc, this, new ForNode(this, init, test, body, modify, flags, controlFlowEscapes, conversion, iterator));
    }

    @Override
    public boolean hasPerIterationScope() {
        return (flags & PER_ITERATION_SCOPE) != 0;
    }

    /**
     * Returns true if this for-node needs the scope creator of its containing block to create per-iteration scope.
     * This is only true for for-in loops with lexical declarations.
     */
    public boolean needsScopeCreator() {
        return isForInOrOf() && hasPerIterationScope();
    }

}
