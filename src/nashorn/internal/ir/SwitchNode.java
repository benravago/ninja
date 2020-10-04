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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nashorn.internal.codegen.Label;
import nashorn.internal.ir.annotations.Immutable;
import nashorn.internal.ir.visitor.NodeVisitor;

/**
 * IR representation of a SWITCH statement.
 */
@Immutable
public final class SwitchNode extends BreakableStatement {

    /** Switch expression. */
    private final Expression expression;

    /** Switch cases. */
    private final List<CaseNode> cases;

    /** Switch default index. */
    private final int defaultCaseIndex;

    /**
     * True if all cases are 32-bit signed integer constants, without repetitions.
     * It's a prerequisite for using a tableswitch/lookupswitch when generating code.
     */
    private final boolean uniqueInteger;

    /** Tag symbol. */
    private final Symbol tag;

    /**
     * Constructor
     */
    public SwitchNode(int lineNumber, long token, int finish, Expression expression, List<CaseNode> cases, CaseNode defaultCase) {
        super(lineNumber, token, finish, new Label("switch_break"));
        this.expression = expression;
        this.cases = cases;
        this.defaultCaseIndex = defaultCase == null ? -1 : cases.indexOf(defaultCase);
        this.uniqueInteger = false;
        this.tag = null;
    }

    private SwitchNode(SwitchNode switchNode, Expression expression, List<CaseNode> cases, int defaultCaseIndex, LocalVariableConversion conversion, boolean uniqueInteger, Symbol tag) {
        super(switchNode, conversion);
        this.expression = expression;
        this.cases = cases;
        this.defaultCaseIndex = defaultCaseIndex;
        this.tag = tag;
        this.uniqueInteger = uniqueInteger;
    }

    @Override
    public Node ensureUniqueLabels(LexicalContext lc) {
        var newCases = new ArrayList<CaseNode>();
        for (var caseNode : cases) {
            newCases.add(new CaseNode(caseNode, caseNode.getTest(), caseNode.getBody(), caseNode.getLocalVariableConversion()));
        }
        return Node.replaceInLexicalContext(lc, this, new SwitchNode(this, expression, newCases, defaultCaseIndex, conversion, uniqueInteger, tag));
    }

    @Override
    public boolean isTerminal() {
        // there must be a default case, and that including all other cases must terminate
        if (!cases.isEmpty() && defaultCaseIndex != -1) {
            for (var caseNode : cases) {
                if (!caseNode.isTerminal()) {
                    return false;
                }
            }
            return true;
        }
        return false;

    }

    @Override
    public Node accept(LexicalContext lc, NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterSwitchNode(this)) {
            return visitor.leaveSwitchNode(
                setExpression(lc, (Expression)expression.accept(visitor)).
                setCases(lc, Node.accept(visitor, cases),
                defaultCaseIndex));
        }

        return this;
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        sb.append("switch (");
        expression.toString(sb, printType);
        sb.append(')');
    }

    /**
     * Return the case node that is default case
     */
    public CaseNode getDefaultCase() {
        return defaultCaseIndex == -1 ? null : cases.get(defaultCaseIndex);
    }

    /**
     * Get the cases in this switch
     */
    public List<CaseNode> getCases() {
        return Collections.unmodifiableList(cases);
    }

    /**
     * Replace case nodes with new list.
     * The cases have to be the same and the default case index the same.
     * This is typically used by NodeVisitors who perform operations on every case node.
     */
    public SwitchNode setCases(LexicalContext lc, List<CaseNode> cases) {
        return setCases(lc, cases, defaultCaseIndex);
    }

    private SwitchNode setCases(LexicalContext lc, List<CaseNode> cases, int defaultCaseIndex) {
        if (this.cases == cases) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new SwitchNode(this, expression, cases, defaultCaseIndex, conversion, uniqueInteger, tag));
    }

    /**
     * Set or reset the list of cases in this switch
     */
    public SwitchNode setCases(LexicalContext lc, List<CaseNode> cases, CaseNode defaultCase) {
        return setCases(lc, cases, defaultCase == null ? -1 : cases.indexOf(defaultCase));
    }

    /**
     * Return the expression to switch on
     */
    public Expression getExpression() {
        return expression;
    }

    /**
     * Set or reset the expression to switch on
     */
    public SwitchNode setExpression(LexicalContext lc, Expression expression) {
        if (this.expression == expression) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new SwitchNode(this, expression, cases, defaultCaseIndex, conversion, uniqueInteger, tag));
    }

    /**
     * Get the tag symbol for this switch.
     * The tag symbol is where the switch expression result is stored
     */
    public Symbol getTag() {
        return tag;
    }

    /**
     * Set the tag symbol for this switch.
     * The tag symbol is where the switch expression result is stored
     */
    public SwitchNode setTag(LexicalContext lc, Symbol tag) {
        if (this.tag == tag) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new SwitchNode(this, expression, cases, defaultCaseIndex, conversion, uniqueInteger, tag));
    }

    /**
     * Returns true if all cases of this switch statement are 32-bit signed integer constants, without repetitions.
     */
    public boolean isUniqueInteger() {
        return uniqueInteger;
    }

    /**
     * Sets whether all cases of this switch statement are 32-bit signed integer constants, without repetitions.
     * 'uniqueInteger', if true, all cases of this switch statement have been determined to be 32-bit signed integer constants, without repetitions.
     */
    public SwitchNode setUniqueInteger(LexicalContext lc, boolean uniqueInteger) {
        if (this.uniqueInteger == uniqueInteger) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new SwitchNode(this, expression, cases, defaultCaseIndex, conversion, uniqueInteger, tag));
    }

    @Override
    JoinPredecessor setLocalVariableConversionChanged(LexicalContext lc, LocalVariableConversion conversion) {
        return Node.replaceInLexicalContext(lc, this, new SwitchNode(this, expression, cases, defaultCaseIndex, conversion, uniqueInteger, tag));
    }

}
