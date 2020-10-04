/*
 * Copyright (c) 2010, 2017, Oracle and/or its affiliates. All rights reserved.
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

import java.io.PrintWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import nashorn.internal.codegen.Label;
import nashorn.internal.ir.annotations.Immutable;
import nashorn.internal.ir.visitor.NodeVisitor;

/**
 * IR representation for a list of statements.
 */
@Immutable
public class Block extends Node implements BreakableNode, Terminal, Flags<Block> {

    /** List of statements */
    protected final List<Statement> statements;

    /** Symbol table - keys must be returned in the order they were put in. */
    protected final Map<String, Symbol> symbols;

    /** Entry label. */
    private final Label entryLabel;

    /** Break label. */
    private final Label breakLabel;

    /** Does the block/function need a new scope? Is this synthetic? */
    protected final int flags;

    /**
     * @see JoinPredecessor
     */
    private final LocalVariableConversion conversion;

    /** Flag indicating that this block needs scope */
    public static final int NEEDS_SCOPE        = 1 << 0;

    /**
     * Is this block tagged as terminal based on its contents (usually the last statement)
     */
    public static final int IS_TERMINAL        = 1 << 2;

    /**
     * Is this block the eager global scope - i.e. the original program.
     * This isn't true for the outermost level of recompiles
     */
    public static final int IS_GLOBAL_SCOPE    = 1 << 3;

    /**
     * Is this block a synthetic one introduced by Parser?
     */
    public static final int IS_SYNTHETIC       = 1 << 4;

    /**
     * Is this the function body block?
     * May not be the first, if parameter list contains expressions.
     */
    public static final int IS_BODY            = 1 << 5;

    /**
     * Is this the parameter initialization block?
     * If present, must be the first block, immediately wrapping the function body block.
     */
    public static final int IS_PARAMETER_BLOCK = 1 << 6;

    /**
     * Marks the variable declaration block for case clauses of a switch statement.
     */
    public static final int IS_SWITCH_BLOCK    = 1 << 7;

    /**
     * Is this block tagged as breakable based on its contents (block having labelled break statement)
     */
    public static final int IS_BREAKABLE       = 1 << 8;

    /**
     * Constructor
     */
    public Block(long token, int finish, int flags, Statement... statements) {
        super(token, finish);
        this.statements = Arrays.asList(statements);
        this.symbols    = new LinkedHashMap<>();
        this.entryLabel = new Label("block_entry");
        this.breakLabel = new Label("block_break");
        var len = statements.length;
        var terminalFlags = len > 0 && statements[len - 1].hasTerminalFlags() ? IS_TERMINAL : 0;
        this.flags = terminalFlags | flags;
        this.conversion = null;
    }

    /**
     * Constructs a new block
     */
    public Block(long token, int finish, Statement...statements){
        this(token, finish, IS_SYNTHETIC, statements);
    }

    /**
     * Constructs a new block
     */
    public Block(long token, int finish, List<Statement> statements){
        this(token, finish, IS_SYNTHETIC, statements);
    }

    /**
     * Constructor
     */
    public Block(long token, int finish, int flags, List<Statement> statements) {
        this(token, finish, flags, statements.toArray(new Statement[0]));
    }

    private Block(Block block, int finish, List<Statement> statements, int flags, Map<String, Symbol> symbols, LocalVariableConversion conversion) {
        super(block, finish);
        this.statements = statements;
        this.flags      = flags;
        this.symbols    = new LinkedHashMap<>(symbols); //todo - symbols have no dependencies on any IR node and can as far as we understand it be shallow copied now
        this.entryLabel = new Label(block.entryLabel);
        this.breakLabel = new Label(block.breakLabel);
        this.conversion = conversion;
    }

    /**
     * Is this block the outermost eager global scope - i.e. the primordial program?
     * Used for global anchor point for scope depth computation for recompilation code
     */
    public boolean isGlobalScope() {
        return getFlag(IS_GLOBAL_SCOPE);
    }

    /**
     * Returns true if this block defines any symbols.
     */
    public boolean hasSymbols() {
        return !symbols.isEmpty();
    }

    /**
     * Replaces symbols defined in this block with different symbols.
     * Used to ensure symbol tables are immutable upon construction and have copy-on-write semantics.
     * Note that this method only replaces the symbols in the symbol table, it does not act on any contained AST nodes that might reference the symbols.
     * Those should be updated separately as this method is meant to be used as part of such an update pass.
     */
    public Block replaceSymbols(LexicalContext lc, Map<Symbol, Symbol> replacements) {
        if (symbols.isEmpty()) {
            return this;
        }
        var newSymbols = new LinkedHashMap<String, Symbol>(symbols);
        for (var entry : newSymbols.entrySet()) {
            var newSymbol = replacements.get(entry.getValue());
            assert newSymbol != null : "Missing replacement for " + entry.getKey();
            entry.setValue(newSymbol);
        }
        return Node.replaceInLexicalContext(lc, this, new Block(this, finish, statements, flags, newSymbols, conversion));
    }

    /**
     * Returns a copy of this block with a shallow copy of the symbol table.
     */
    public Block copyWithNewSymbols() {
        return new Block(this, finish, statements, flags, new LinkedHashMap<>(symbols), conversion);
    }

    @Override
    public Node ensureUniqueLabels(LexicalContext lc) {
        return Node.replaceInLexicalContext(lc, this, new Block(this, finish, statements, flags, symbols, conversion));
    }

    /**
     * Assist in IR navigation.
     */
    @Override
    public Node accept(LexicalContext lc, NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterBlock(this)) {
            return visitor.leaveBlock(setStatements(lc, Node.accept(visitor, statements)));
        }
        return this;
    }

    /**
     * Get a copy of the list for all the symbols defined in this block
     */
    public List<Symbol> getSymbols() {
        return symbols.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(symbols.values()));
    }

    /**
     * Retrieves an existing symbol defined in the current block.
     */
    public Symbol getExistingSymbol(String name) {
        return symbols.get(name);
    }

    /**
     * Test if this block represents a <code>catch</code> block in a <code>try</code> statement.
     * This is used by the Splitter as catch blocks are not be subject to splitting.
     */
    public boolean isCatchBlock() {
        return statements.size() == 1 && statements.get(0) instanceof CatchNode;
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        for (var statement : statements) {
            statement.toString(sb, printType);
            sb.append(';');
        }
    }

    /**
     * Print symbols in block in alphabetical order, sorted on name.
     * Used for debugging, see the --print-symbols flag
     */
    public boolean printSymbols(PrintWriter stream) {
        var values = new ArrayList<Symbol>(symbols.values());

        Collections.sort(values, new Comparator<Symbol>() {
            @Override
            public int compare(Symbol s0, Symbol s1) {
                return s0.getName().compareTo(s1.getName());
            }
        });

        for (var symbol : values) {
            symbol.print(stream);
        }

        return !values.isEmpty();
    }

    /**
     * Tag block as terminal or non terminal
     */
    public Block setIsTerminal(LexicalContext lc, boolean isTerminal) {
        return isTerminal ? setFlag(lc, IS_TERMINAL) : clearFlag(lc, IS_TERMINAL);
    }

    @Override
    public int getFlags() {
        return flags;
    }

    /**
     * Is this a terminal block, i.e. does it end control flow like ending with a throw or return?
     */
    @Override
    public boolean isTerminal() {
        return getFlag(IS_TERMINAL) && !getFlag(IS_BREAKABLE);
    }

    /**
     * Get the entry label for this block
     */
    public Label getEntryLabel() {
        return entryLabel;
    }

    @Override
    public Label getBreakLabel() {
        return breakLabel;
    }

    @Override
    public Block setLocalVariableConversion(LexicalContext lc, LocalVariableConversion conversion) {
        if (this.conversion == conversion) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new Block(this, finish, statements, flags, symbols, conversion));
    }

    @Override
    public LocalVariableConversion getLocalVariableConversion() {
        return conversion;
    }

    /**
     * Get the list of statements in this block
     */
    public List<Statement> getStatements() {
        return Collections.unmodifiableList(statements);
    }

    /**
     * Returns the number of statements in the block.
     */
    public int getStatementCount() {
        return statements.size();
    }

    /**
     * Returns the line number of the first statement in the block.
     */
    public int getFirstStatementLineNumber() {
        if (statements == null || statements.isEmpty()) {
            return -1;
        }
        return statements.get(0).getLineNumber();
    }

    /**
     * Returns the last statement in the block.
     */
    public Statement getLastStatement() {
        return statements.isEmpty() ? null : statements.get(statements.size() - 1);
    }

    /**
     * Reset the statement list for this block
     */
    public Block setStatements(LexicalContext lc, List<Statement> statements) {
        if (this.statements == statements) {
            return this;
        }
        var lastFinish = 0;
        if (!statements.isEmpty()) {
            lastFinish = statements.get(statements.size() - 1).getFinish();
        }
        return Node.replaceInLexicalContext(lc, this, new Block(this, Math.max(finish, lastFinish), statements, flags, symbols, conversion));
    }

    /**
     * Add or overwrite an existing symbol in the block
     */
    public void putSymbol(Symbol symbol) {
        symbols.put(symbol.getName(), symbol);
    }

    /**
     * Check whether scope is necessary for this Block
     */
    public boolean needsScope() {
        return (flags & NEEDS_SCOPE) == NEEDS_SCOPE;
    }

    /**
     * Check whether this block is synthetic or not.
     */
    public boolean isSynthetic() {
        return (flags & IS_SYNTHETIC) == IS_SYNTHETIC;
    }

    @Override
    public Block setFlags(LexicalContext lc, int flags) {
        if (this.flags == flags) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new Block(this, finish, statements, flags, symbols, conversion));
    }

    @Override
    public Block clearFlag(LexicalContext lc, int flag) {
        return setFlags(lc, flags & ~flag);
    }

    @Override
    public Block setFlag(LexicalContext lc, int flag) {
        return setFlags(lc, flags | flag);
    }

    @Override
    public boolean getFlag(int flag) {
        return (flags & flag) == flag;
    }

    /**
     * Set the needs scope flag.
     */
    public Block setNeedsScope(LexicalContext lc) {
        if (needsScope()) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new Block(this, finish, statements, flags | NEEDS_SCOPE, symbols, conversion));
    }

    /**
     * Computationally determine the next slot for this block, indexed from 0.
     * Use this as a relative base when computing frames
     */
    public int nextSlot() {
        var next = 0;
        for (var symbol : getSymbols()) {
            if (symbol.hasSlot()) {
                next += symbol.slotCount();
            }
        }
        return next;
    }

    /**
     * Determine whether this block needs to provide its scope object creator for use by its child nodes.
     * This is only necessary for synthetic parent blocks of for-in loops with lexical declarations.
     * @see ForNode#needsScopeCreator()
     */
    public boolean providesScopeCreator() {
        return needsScope() && isSynthetic() && (getLastStatement() instanceof ForNode) && ((ForNode) getLastStatement()).needsScopeCreator();
    }

    @Override
    public boolean isBreakableWithoutLabel() {
        return false;
    }

    @Override
    public List<Label> getLabels() {
        return Collections.unmodifiableList(Arrays.asList(entryLabel, breakLabel));
    }

    @Override
    public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
        return Acceptor.accept(this, visitor);
    }

    /**
     * Checks if this is a function body.
     */
    public boolean isFunctionBody() {
        return getFlag(IS_BODY);
    }

    /**
     * Checks if this is a parameter block.
     */
    public boolean isParameterBlock() {
        return getFlag(IS_PARAMETER_BLOCK);
    }

    /**
     * Checks whether this is a switch block.
     */
    public boolean isSwitchBlock() {
        return getFlag(IS_SWITCH_BLOCK);
    }

}
