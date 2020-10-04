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

import java.util.Collections;
import java.util.List;

import nashorn.internal.codegen.Label;
import nashorn.internal.ir.annotations.Immutable;
import nashorn.internal.ir.visitor.NodeVisitor;

/**
 * IR representation of CASE clause.
 *
 * Case nodes are not BreakableNodes, but the SwitchNode is
 */
@Immutable
public final class CaseNode extends Node implements JoinPredecessor, Labels, Terminal {

    /** Test expression. */
    private final Expression test;

    /** Statements. */
    private final Block body;

    /** Case entry label. */
    private final Label entry;

    /**
     * @see JoinPredecessor
     */
    private final LocalVariableConversion conversion;

    /**
     * Constructors
     */
    public CaseNode(long token, int finish, Expression test, Block body) {
        super(token, finish);
        this.test = test;
        this.body = body;
        this.entry = new Label("entry");
        this.conversion = null;
    }

    CaseNode(CaseNode caseNode, Expression test, Block body, LocalVariableConversion conversion) {
        super(caseNode);
        this.test = test;
        this.body = body;
        this.entry = new Label(caseNode.entry);
        this.conversion = conversion;
    }

    /**
     * Is this a terminal case node, i.e. does it end control flow like having a throw or return?
     */
    @Override
    public boolean isTerminal() {
        return body.isTerminal();
    }

    /**
     * Assist in IR navigation.
     */
    @Override
    public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterCaseNode(this)) {
            var newTest = test == null ? null : (Expression)test.accept(visitor);
            var newBody = body == null ? null : (Block)body.accept(visitor);
            return visitor.leaveCaseNode(setTest(newTest).setBody(newBody));
        }
        return this;
    }

    @Override
    public void toString(StringBuilder sb, boolean printTypes) {
        if (test != null) {
            sb.append("case ");
            test.toString(sb, printTypes);
            sb.append(':');
        } else {
            sb.append("default:");
        }
    }

    /**
     * Get the body for this case node
     */
    public Block getBody() {
        return body;
    }

    /**
     * Get the entry label for this case node
     */
    public Label getEntry() {
        return entry;
    }

    /**
     * Get the test expression for this case node
     */
    public Expression getTest() {
        return test;
    }

    /**
     * Reset the test expression for this case node
     */
    public CaseNode setTest(Expression test) {
        if (this.test == test) {
            return this;
        }
        return new CaseNode(this, test, body, conversion);
    }

    @Override
    public JoinPredecessor setLocalVariableConversion(LexicalContext lc, LocalVariableConversion conversion) {
        if (this.conversion == conversion) {
            return this;
        }
        return new CaseNode(this, test, body, conversion);
    }

    @Override
    public LocalVariableConversion getLocalVariableConversion() {
        return conversion;
    }

    private CaseNode setBody(Block body) {
        if (this.body == body) {
            return this;
        }
        return new CaseNode(this, test, body, conversion);
    }

    @Override
    public List<Label> getLabels() {
        return Collections.unmodifiableList(Collections.singletonList(entry));
    }

}
