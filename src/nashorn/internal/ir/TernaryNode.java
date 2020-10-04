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
 * TernaryNode represent the ternary operator {@code ?:}. Note that for control-flow calculation reasons its branch
 * expressions (but not its test expression) are always wrapped in instances of {@link JoinPredecessorExpression}.
 */
@Immutable
public final class TernaryNode extends Expression {

    private final Expression test;
    private final JoinPredecessorExpression trueExpr;
    private final JoinPredecessorExpression falseExpr;

    /**
     * Constructor
     */
    public TernaryNode(long token, Expression test, JoinPredecessorExpression trueExpr, JoinPredecessorExpression falseExpr) {
        super(token, falseExpr.getFinish());
        this.test = test;
        this.trueExpr = trueExpr;
        this.falseExpr = falseExpr;
    }

    private TernaryNode(TernaryNode ternaryNode, Expression test, JoinPredecessorExpression trueExpr, JoinPredecessorExpression falseExpr) {
        super(ternaryNode);
        this.test = test;
        this.trueExpr = trueExpr;
        this.falseExpr = falseExpr;
    }

    @Override
    public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterTernaryNode(this)) {
            var newTest = (Expression)getTest().accept(visitor);
            var newTrueExpr = (JoinPredecessorExpression)trueExpr.accept(visitor);
            var newFalseExpr = (JoinPredecessorExpression)falseExpr.accept(visitor);
            return visitor.leaveTernaryNode(setTest(newTest).setTrueExpression(newTrueExpr).setFalseExpression(newFalseExpr));
        }
        return this;
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        var tokenType  = tokenType();
        var testParen  = tokenType.needsParens(getTest().tokenType(), true);
        var trueParen  = tokenType.needsParens(getTrueExpression().tokenType(), false);
        var falseParen = tokenType.needsParens(getFalseExpression().tokenType(), false);

        if (testParen) {
            sb.append('(');
        }
        getTest().toString(sb, printType);
        if (testParen) {
            sb.append(')');
        }

        sb.append(" ? ");

        if (trueParen) {
            sb.append('(');
        }
        getTrueExpression().toString(sb, printType);
        if (trueParen) {
            sb.append(')');
        }

        sb.append(" : ");

        if (falseParen) {
            sb.append('(');
        }
        getFalseExpression().toString(sb, printType);
        if (falseParen) {
            sb.append(')');
        }
    }

    @Override
    public boolean isLocal() {
        return getTest().isLocal() && getTrueExpression().isLocal() && getFalseExpression().isLocal();
    }

    @Override
    public Type getType() {
        return Type.widestReturnType(getTrueExpression().getType(), getFalseExpression().getType());
    }

    /**
     * Get the test expression for this ternary expression, i.e. "x" in x ? y : z
     */
    public Expression getTest() {
        return test;
    }

    /**
     * Get the true expression for this ternary expression, i.e. "y" in x ? y : z
     */
    public JoinPredecessorExpression getTrueExpression() {
        return trueExpr;
    }

    /**
     * Get the false expression for this ternary expression, i.e. "z" in x ? y : z
     */
    public JoinPredecessorExpression getFalseExpression() {
        return falseExpr;
    }

    /**
     * Set the test expression for this node
     */
    public TernaryNode setTest(Expression test) {
        if (this.test == test) {
            return this;
        }
        return new TernaryNode(this, test, trueExpr, falseExpr);
    }

    /**
     * Set the true expression for this node
     */
    public TernaryNode setTrueExpression(JoinPredecessorExpression trueExpr) {
        if (this.trueExpr == trueExpr) {
            return this;
        }
        return new TernaryNode(this, test, trueExpr, falseExpr);
    }

    /**
     * Set the false expression for this node
     */
    public TernaryNode setFalseExpression(JoinPredecessorExpression falseExpr) {
        if (this.falseExpr == falseExpr) {
            return this;
        }
        return new TernaryNode(this, test, trueExpr, falseExpr);
    }

}
