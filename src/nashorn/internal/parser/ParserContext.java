/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package nashorn.internal.parser;

import java.util.Iterator;
import java.util.NoSuchElementException;
import nashorn.internal.ir.Statement;

/**
 * A class that tracks the current lexical context of node visitation as a stack of {@code ParserContextNode} nodes.
 * Has special methods to retrieve useful subsets of the context.
 * <p>
 * This is implemented with a primitive array and a stack pointer, because it really makes a difference
 * performance wise. None of the collection classes were optimal
 */

class ParserContext {

    private ParserContextNode[] stack;
    private int sp;

    private static final int INITIAL_DEPTH = 16;

    /**
     * Constructs a ParserContext,
     * initializes the stack
     */
    public ParserContext(){
        this.sp = 0;
        this.stack = new ParserContextNode[INITIAL_DEPTH];
    }

    /**
     * Pushes a new block on top of the context, making it the innermost open block.
     */
    public <T extends ParserContextNode> T push(T node) {
        assert !contains(node);
        if (sp == stack.length) {
            var newStack = new ParserContextNode[sp * 2];
            System.arraycopy(stack, 0, newStack, 0, sp);
            stack = newStack;
        }
        stack[sp] = node;
        sp++;

        return node;
    }

    /**
     * Returns the topmost node on the stack
     */
    public ParserContextNode peek() {
        return stack[sp - 1];
    }

    /**
     * Removes and returns the topmost Node from the stack.
     * @param node The node expected to be popped, used for sanity check
     * @return The removed node
     */
    public <T extends ParserContextNode> T pop(T node) {
        --sp;
        @SuppressWarnings("unchecked")
        var popped = (T)stack[sp];
        stack[sp] = null;
        assert node == popped;

        return popped;
    }

    /**
     * Tests if a node is on the stack.
     * @param node  The node to test
     * @return true if stack contains node, false otherwise
     */
    public boolean contains(ParserContextNode node) {
        for (var i = 0; i < sp; i++) {
            if (stack[i] == node) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the topmost {@link ParserContextBreakableNode} on the stack, null if none on stack
     * @return Returns the topmost {@link ParserContextBreakableNode} on the stack, null if none on stack
     */
    private ParserContextBreakableNode getBreakable() {
        for (var iter = new NodeIterator<ParserContextBreakableNode>(ParserContextBreakableNode.class, getCurrentFunction()); iter.hasNext(); ) {
            var next = iter.next();
            if (next.isBreakableWithoutLabel()) {
                return next;
            }
        }
        return null;
    }



    /**
     * Find the breakable node corresponding to this label.
     * @param labelName name of the label to search for.
     * If null, the closest breakable node will be returned unconditionally, e.g. a while loop with no label
     * @return closest breakable node
     */
    public ParserContextBreakableNode getBreakable(String labelName) {
        if (labelName != null) {
            var foundLabel = findLabel(labelName);
            if (foundLabel != null) {
                // iterate to the nearest breakable to the foundLabel
                ParserContextBreakableNode breakable = null;
                for (var iter = new NodeIterator<ParserContextBreakableNode>(ParserContextBreakableNode.class, foundLabel); iter.hasNext(); ) {
                    breakable = iter.next();
                }
                return breakable;
            }
            return null;
        }
        return getBreakable();
    }

    /**
     * Returns the loop node of the current loop, or null if not inside a loop
     */
    public ParserContextLoopNode getCurrentLoop() {
        var iter = new NodeIterator<ParserContextLoopNode>(ParserContextLoopNode.class, getCurrentFunction());
        return iter.hasNext() ? iter.next() : null;
    }

    private ParserContextLoopNode getContinueTo() {
        return getCurrentLoop();
    }

    /**
     * Find the continue target node corresponding to this label.
     * @param labelName label name to search for.
     * If null the closest loop node will be returned unconditionally, e.g. a while loop with no label
     * @return closest continue target node
     */
    public ParserContextLoopNode getContinueTo(String labelName) {
        if (labelName != null) {
            var foundLabel = findLabel(labelName);
            if (foundLabel != null) {
                // iterate to the nearest loop to the foundLabel
                ParserContextLoopNode loop = null;
                for (var iter = new NodeIterator<ParserContextLoopNode>(ParserContextLoopNode.class, foundLabel); iter.hasNext(); ) {
                    loop = iter.next();
                }
                return loop;
            }
            return null;
        }
        return getContinueTo();
    }

    /**
     * Get the function body of a function node on the stack.
     * This will trigger an assertion if node isn't present
     */
    public ParserContextBlockNode getFunctionBody(ParserContextFunctionNode functionNode) {
        for (var i = sp - 1; i >= 0 ; i--) {
            if (stack[i] == functionNode) {
                return (ParserContextBlockNode)stack[i + 1];
            }
        }
        throw new AssertionError(functionNode.getName() + " not on context stack");
    }

    /**
     * Check the stack for a given label node by name
     * @param name name of the label
     * @return LabelNode if found, null otherwise
     */
    public ParserContextLabelNode findLabel(String name) {
        for (var iter = new NodeIterator<ParserContextLabelNode>(ParserContextLabelNode.class, getCurrentFunction()); iter.hasNext(); ) {
            var next = iter.next();
            if (next.getLabelName().equals(name)) {
                return next;
            }
        }
        return null;
    }

    /**
     * Prepends a statement to the current node.
     */
    public void prependStatementToCurrentNode(Statement statement) {
        assert statement != null;
        stack[sp - 1].prependStatement(statement);
    }

    /**
     * Appends a statement to the current Node.
     */
    public void appendStatementToCurrentNode(Statement statement) {
        assert statement != null;
        stack[sp - 1].appendStatement(statement);
    }

    /**
     * Returns the innermost function in the context.
     */
    public ParserContextFunctionNode getCurrentFunction() {
        for (var i = sp - 1; i >= 0; i--) {
            if (stack[i] instanceof ParserContextFunctionNode) {
                return (ParserContextFunctionNode) stack[i];
            }
        }
        return null;
    }

    /**
     * Returns an iterator over all blocks in the context, with the top block (innermost lexical context) first.
     */
    public Iterator<ParserContextBlockNode> getBlocks() {
        return new NodeIterator<>(ParserContextBlockNode.class);
    }

    /**
     * Returns the innermost block in the context.
     */
    public ParserContextBlockNode getCurrentBlock() {
        return getBlocks().next();
    }

    /**
     * The last statement added to the context
     */
    public Statement getLastStatement() {
        if (sp == 0) {
            return null;
        }
        var top = stack[sp - 1];
        var s = top.getStatements().size();
        return s == 0 ? null : top.getStatements().get(s - 1);
    }

    /**
     * Returns an iterator over all functions in the context, with the top (innermost open) function first.
     */
    public Iterator<ParserContextFunctionNode> getFunctions() {
        return new NodeIterator<>(ParserContextFunctionNode.class);
    }

    private class NodeIterator<T extends ParserContextNode> implements Iterator<T> {
        private int index;
        private T next;
        private final Class<T> clazz;
        private ParserContextNode until;

        NodeIterator(Class<T> clazz) {
            this(clazz, null);
        }

        NodeIterator(Class<T> clazz, ParserContextNode until) {
            this.index = sp - 1;
            this.clazz = clazz;
            this.until = until;
            this.next  = findNext();
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public T next() {
            if (next == null) {
                throw new NoSuchElementException();
            }
            var lnext = next;
            next = findNext();
            return lnext;
        }

        @SuppressWarnings("unchecked")
        private T findNext() {
            for (var i = index; i >= 0; i--) {
                var node = stack[i];
                if (node == until) {
                    return null;
                }
                if (clazz.isAssignableFrom(node.getClass())) {
                    index = i - 1;
                    return (T)node;
                }
            }
            return null;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

}
