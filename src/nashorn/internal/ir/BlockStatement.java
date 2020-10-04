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

import java.util.List;

import nashorn.internal.ir.visitor.NodeVisitor;

/**
 * Represents a block used as a statement.
 */
public class BlockStatement extends Statement {

    /** Block to execute. */
    private final Block block;

    /**
     * Constructor
     */
    public BlockStatement(Block block) {
        this(block.getFirstStatementLineNumber(), block);
    }

    /**
     * Constructor
     */
    public BlockStatement(int lineNumber, Block block) {
        super(lineNumber, block.getToken(), block.getFinish());
        this.block = block;
    }

    private BlockStatement(BlockStatement blockStatement, Block block) {
        super(blockStatement);
        this.block = block;
    }

    /**
     * Use this method to create a block statement meant to replace a single statement.
     */
    public static BlockStatement createReplacement(Statement stmt, List<Statement> newStmts) {
        return createReplacement(stmt, stmt.getFinish(), newStmts);
    }

    /**
     * Use this method to create a block statement meant to replace a single statement.
     */
    public static BlockStatement createReplacement(Statement stmt, int finish, List<Statement> newStmts) {
        return new BlockStatement(stmt.getLineNumber(), new Block(stmt.getToken(), finish, newStmts));
    }

    @Override
    public boolean isTerminal() {
        return block.isTerminal();
    }

    /**
     * Tells if this is a synthetic block statement or not.
     */
    public boolean isSynthetic() {
        return block.isSynthetic();
    }

    @Override
    public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterBlockStatement(this)) {
            return visitor.leaveBlockStatement(setBlock((Block)block.accept(visitor)));
        }
        return this;
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        block.toString(sb, printType);
    }

    /**
     * Return the block to be executed
     */
    public Block getBlock() {
        return block;
    }

    /**
     * Reset the block to be executed
     */
    public BlockStatement setBlock(Block block) {
        if (this.block == block) {
            return this;
        }
        return new BlockStatement(this, block);
    }

}
