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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nashorn.internal.ir.annotations.Immutable;
import nashorn.internal.ir.visitor.NodeVisitor;

/**
 * IR representation of a TRY statement.
 */
@Immutable
public final class TryNode extends LexicalContextStatement implements JoinPredecessor {

    /** Try statements. */
    private final Block body;

    /** List of catch clauses. */
    private final List<Block> catchBlocks;

    /** Finally clause. */
    private final Block finallyBody;

    /**
     * List of inlined finally blocks.
     * The structure of every inlined finally is: Block(LabelNode(label, Block(finally-statements, (JumpStatement|ReturnNode)?))).
     * That is, the block has a single LabelNode statement with the label and a block containing the statements of the inlined finally block with the jump or return statement appended (if the finally block was not terminal; the original jump/return is simply ignored if the finally block itself terminates).
     * The reason for this somewhat strange arrangement is that we didn't want to create a separate class for the (label, BlockStatement pair) but rather reused the already available LabelNode.
     * However, if we simply used List&lt;LabelNode&gt; without wrapping the label nodes in an additional Block, that would've thrown off visitors relying on BlockLexicalContext -- same reason why we never use Statement as the type of bodies of e.g. IfNode, WhileNode etc. but rather blockify them even when they're single statements.
     */
    private final List<Block> inlinedFinallies;

    /** Exception symbol. */
    private final Symbol exception;

    private final LocalVariableConversion conversion;

    /**
     * Constructor
     */
    public TryNode(int lineNumber, long token, int finish, Block body, List<Block> catchBlocks, Block finallyBody) {
        super(lineNumber, token, finish);
        this.body = body;
        this.catchBlocks = catchBlocks;
        this.finallyBody = finallyBody;
        this.conversion = null;
        this.inlinedFinallies = Collections.emptyList();
        this.exception = null;
    }

    private TryNode(TryNode tryNode, Block body, List<Block> catchBlocks, Block finallyBody, LocalVariableConversion conversion, List<Block> inlinedFinallies, Symbol exception) {
        super(tryNode);
        this.body = body;
        this.catchBlocks = catchBlocks;
        this.finallyBody = finallyBody;
        this.conversion = conversion;
        this.inlinedFinallies = inlinedFinallies;
        this.exception = exception;
    }

    @Override
    public Node ensureUniqueLabels(LexicalContext lc) {
        //try nodes are never in lex context
        return new TryNode(this, body, catchBlocks, finallyBody, conversion, inlinedFinallies, exception);
    }

    @Override
    public boolean isTerminal() {
        if (body.isTerminal()) {
            for (var catchBlock : getCatchBlocks()) {
                if (!catchBlock.isTerminal()) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Assist in IR navigation.
     */
    @Override
    public Node accept(LexicalContext lc, NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterTryNode(this)) {
            // Need to do finallybody first for termination analysis. TODO still necessary?
            var newFinallyBody = finallyBody == null ? null : (Block)finallyBody.accept(visitor);
            var newBody = (Block)body.accept(visitor);
            return visitor.leaveTryNode(
                setBody(lc, newBody).
                setFinallyBody(lc, newFinallyBody).
                setCatchBlocks(lc, Node.accept(visitor, catchBlocks)).
                setInlinedFinallies(lc, Node.accept(visitor, inlinedFinallies)));
        }
        return this;
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        sb.append("try ");
    }

    /**
     * Get the body for this try block
     */
    public Block getBody() {
        return body;
    }

    /**
     * Reset the body of this try block
     */
    public TryNode setBody(LexicalContext lc, Block body) {
        if (this.body == body) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new TryNode(this,  body, catchBlocks, finallyBody, conversion, inlinedFinallies, exception));
    }

    /**
     * Get the catches for this try block
     */
    public List<CatchNode> getCatches() {
        var catches = new ArrayList<CatchNode>(catchBlocks.size());
        for (var catchBlock : catchBlocks) {
            catches.add(getCatchNodeFromBlock(catchBlock));
        }
        return Collections.unmodifiableList(catches);
    }

    private static CatchNode getCatchNodeFromBlock(Block catchBlock) {
        return (CatchNode)catchBlock.getStatements().get(0);
    }

    /**
     * Get the catch blocks for this try block
     */
    public List<Block> getCatchBlocks() {
        return Collections.unmodifiableList(catchBlocks);
    }

    /**
     * Set the catch blocks of this try
     */
    public TryNode setCatchBlocks(LexicalContext lc, List<Block> catchBlocks) {
        if (this.catchBlocks == catchBlocks) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new TryNode(this, body, catchBlocks, finallyBody, conversion, inlinedFinallies, exception));
    }

    /**
     * Get the exception symbol for this try block
     */
    public Symbol getException() {
        return exception;
    }
    /**
     * Set the exception symbol for this try block
     */
    public TryNode setException(LexicalContext lc, Symbol exception) {
        if (this.exception == exception) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new TryNode(this, body, catchBlocks, finallyBody, conversion, inlinedFinallies, exception));
    }

    /**
     * Get the body of the finally clause for this try
     */
    public Block getFinallyBody() {
        return finallyBody;
    }

    /**
     * Get the inlined finally block with the given label name.
     * This returns the actual finally block in the {@link LabelNode}, not the outer wrapper block for the {@link LabelNode}.
     */
    public Block getInlinedFinally(String labelName) {
        for (var inlinedFinally: inlinedFinallies) {
            var labelNode = getInlinedFinallyLabelNode(inlinedFinally);
            if (labelNode.getLabelName().equals(labelName)) {
                return labelNode.getBody();
            }
        }
        return null;
    }

    private static LabelNode getInlinedFinallyLabelNode(Block inlinedFinally) {
        return (LabelNode)inlinedFinally.getStatements().get(0);
    }

    /**
     * Given an outer wrapper block for the {@link LabelNode} as returned by {@link #getInlinedFinallies()}, returns its actual inlined finally block.
     * 'inlinedFinally' is the outer block for inlined finally, as returned as an element of {@link #getInlinedFinallies()}.
     * Returns the block contained in the {@link LabelNode} contained in the passed block.
     */
    public static Block getLabelledInlinedFinallyBlock(Block inlinedFinally) {
        return getInlinedFinallyLabelNode(inlinedFinally).getBody();
    }

    /**
     * Returns a list of inlined finally blocks.
     * Note that this returns a list of {@link Block}s such that each one of them has a single {@link LabelNode}, which in turn contains the label name for the finally block and the actual finally block.
     * To safely extract the actual finally block, use {@link #getLabelledInlinedFinallyBlock(Block)}.
     */
    public List<Block> getInlinedFinallies() {
        return Collections.unmodifiableList(inlinedFinallies);
    }

    /**
     * Set the finally body of this try
     */
    public TryNode setFinallyBody(LexicalContext lc, Block finallyBody) {
        if (this.finallyBody == finallyBody) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new TryNode(this, body, catchBlocks, finallyBody, conversion, inlinedFinallies, exception));
    }

    /**
     * Set the inlined finally blocks of this try.
     * Each element should be a block with a single statement that is a {@link LabelNode} with a unique label, and the block within the label node should contain the actual inlined finally block.
     */
    public TryNode setInlinedFinallies(LexicalContext lc, List<Block> inlinedFinallies) {
        if (this.inlinedFinallies == inlinedFinallies) {
            return this;
        }
        assert checkInlinedFinallies(inlinedFinallies);
        return Node.replaceInLexicalContext(lc, this, new TryNode(this, body, catchBlocks, finallyBody, conversion, inlinedFinallies, exception));
    }

    private static boolean checkInlinedFinallies(List<Block> inlinedFinallies) {
        if (!inlinedFinallies.isEmpty()) {
            var labels = new HashSet<String>();
            for (var inlinedFinally : inlinedFinallies) {
                var stmts = inlinedFinally.getStatements();
                assert stmts.size() == 1;
                var ln = getInlinedFinallyLabelNode(inlinedFinally);
                assert labels.add(ln.getLabelName()); // unique label
            }
        }
        return true;
    }

    @Override
    public JoinPredecessor setLocalVariableConversion(LexicalContext lc, LocalVariableConversion conversion) {
        if (this.conversion == conversion) {
            return this;
        }
        return new TryNode(this, body, catchBlocks, finallyBody, conversion, inlinedFinallies, exception);
    }

    @Override
    public LocalVariableConversion getLocalVariableConversion() {
        return conversion;
    }

}
