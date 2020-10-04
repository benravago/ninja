/*
 * Copyright (c) 2010, 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.io.File;

import java.util.Iterator;
import java.util.NoSuchElementException;

import nashorn.internal.Util;

/**
 * A class that tracks the current lexical context of node visitation as a stack of {@link Block} nodes.
 *
 * Has special methods to retrieve useful subsets of the context.
 *
 * This is implemented with a primitive array and a stack pointer, because it really makes a difference performance-wise. None of the collection classes were optimal.
 */
public class LexicalContext {

    private LexicalContextNode[] stack;

    private int[] flags;
    private int sp;

    /**
     * Creates a new empty lexical context.
     */
    public LexicalContext() {
        stack = new LexicalContextNode[16];
        flags = new int[16];
    }

    /**
     * Set the flags for a lexical context node on the stack.
     * Does not replace the flags, but rather adds to them.
     */
    public void setFlag(LexicalContextNode node, int flag) {
        if (flag != 0) {
            // Use setBlockNeedsScope() instead
            assert !(flag == Block.NEEDS_SCOPE && node instanceof Block);

            for (var i = sp - 1; i >= 0; i--) {
                if (stack[i] == node) {
                    flags[i] |= flag;
                    return;
                }
            }
        }
        assert false; // should not be reached
    }

    /**
     * Marks the block as one that creates a scope.
     * Note that this method must be used instead of {@link #setFlag(LexicalContextNode, int)} with {@link Block#NEEDS_SCOPE} because it atomically also sets the {@link FunctionNode#HAS_SCOPE_BLOCK} flag on the block's containing function.
     */
    public void setBlockNeedsScope(Block block) {
        for (var i = sp - 1; i >= 0; i--) {
            if (stack[i] == block) {
                flags[i] |= Block.NEEDS_SCOPE;
                for (var j = i - 1; j >=0; j --) {
                    if (stack[j] instanceof FunctionNode) {
                        flags[j] |= FunctionNode.HAS_SCOPE_BLOCK;
                        return;
                    }
                }
            }
        }
        assert false; // should not be reached
    }

    /**
     * Get the flags for a lexical context node on the stack.
     */
    public int getFlags(LexicalContextNode node) {
        for (var i = sp - 1; i >= 0; i--) {
            if (stack[i] == node) {
                return flags[i];
            }
        }
        throw new AssertionError("flag node not on context stack");
    }

    /**
     * Get the function body of a function node on the lexical context stack.
     * This will trigger an assertion if node isn't present.
     */
    public Block getFunctionBody(FunctionNode functionNode) {
        for (var i = sp - 1; i >= 0 ; i--) {
            if (stack[i] == functionNode) {
                return (Block)stack[i + 1];
            }
        }
        throw new AssertionError(functionNode.getName() + " not on context stack");
    }

    /**
     * Returns all nodes in the LexicalContext.
     */
    public Iterator<LexicalContextNode> getAllNodes() {
        return new NodeIterator<>(LexicalContextNode.class);
    }

    /**
     * Returns the outermost function in this context.
     * It is either the program, or a lazily compiled function.
     */
    public FunctionNode getOutermostFunction() {
        return (FunctionNode)stack[0];
    }

    /**
     * Pushes a new block on top of the context, making it the innermost open block.
     */
    public <T extends LexicalContextNode> T push(T node) {
        assert !contains(node);
        if (sp == stack.length) {
            var newStack = new LexicalContextNode[sp * 2];
            System.arraycopy(stack, 0, newStack, 0, sp);
            stack = newStack;

            var newFlags = new int[sp * 2];
            System.arraycopy(flags, 0, newFlags, 0, sp);
            flags = newFlags;

        }
        stack[sp] = node;
        flags[sp] = 0;

        sp++;

        return node;
    }

    /**
     * Is the context empty?
     */
    public boolean isEmpty() {
        return sp == 0;
    }

    /**
     * Returns the depth of the lexical context.
     */
    public int size() {
        return sp;
    }

    /**
     * Pops the innermost block off the context and all nodes that has been contributed since it was put there.
     * <T> is the type of the node to be popped.
     * 'node' is the node expected to be popped, used to detect unbalanced pushes/pops.
     */
    @SuppressWarnings("unchecked")
    public <T extends Node> T pop(T node) {
        --sp;
        var popped = stack[sp];
        stack[sp] = null;
        if (popped instanceof Flags) {
            return (T)((Flags<?>)popped).setFlag(this, flags[sp]);
        }
        return (T)popped;
    }

    /**
     * Explicitly apply flags to the topmost element on the stack.
     * This is only valid to use from a {@code NodeVisitor.leaveXxx()} method and only on the node being exited at the time.
     * It is not mandatory to use, as {@link #pop(Node)} will apply the flags automatically, but this method can be used to apply them during the {@code leaveXxx()} method in case its logic depends on the value of the flags.
     * <T> is the type of the node to apply the flags to.
     * 'node' is the node to apply the flags to. Must be the topmost node on the stack.
     */
    public <T extends LexicalContextNode & Flags<T>> T applyTopFlags(T node) {
        assert node == peek();
        return node.setFlag(this, flags[sp - 1]);
    }

    /**
     * Return the top element in the context.
     */
    public LexicalContextNode peek() {
        return stack[sp - 1];
    }

    /**
     * Check if a node is in the lexical context.
     */
    public boolean contains(LexicalContextNode node) {
        for (var i = 0; i < sp; i++) {
            if (stack[i] == node) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replace a node on the lexical context with a new one.
     * Normally you should try to engineer IR traversals so this isn't needed
     */
    public LexicalContextNode replace(LexicalContextNode oldNode, LexicalContextNode newNode) {
        for (var i = sp - 1; i >= 0; i--) {
            if (stack[i] == oldNode) {
                assert i == sp - 1 : "violation of contract - we always expect to find the replacement node on top of the lexical context stack: " + newNode + " has " + stack[i + 1].getClass() + " above it";
                stack[i] = newNode;
                break;
            }
         }
        return newNode;
    }

    /**
     * Returns an iterator over all blocks in the context, with the top block (innermost lexical context) first.
     */
    public Iterator<Block> getBlocks() {
        return new NodeIterator<>(Block.class);
    }

    /**
     * Returns an iterator over all functions in the context, with the top (innermost open) function first.
     */
    public Iterator<FunctionNode> getFunctions() {
        return new NodeIterator<>(FunctionNode.class);
    }

    /**
     * Get the parent block for the current lexical context block
     */
    public Block getParentBlock() {
        var iter = new NodeIterator<>(Block.class, getCurrentFunction());
        iter.next();
        return iter.hasNext() ? iter.next() : null;
    }

    /**
     * Gets the label node of the current block.
     * Returns the label node of the current block, if it is labeled. Otherwise returns {@code null}.
     */
    public LabelNode getCurrentBlockLabelNode() {
        assert stack[sp - 1] instanceof Block;
        if (sp < 2) {
            return null;
        }
        var parent = stack[sp - 2];
        return parent instanceof LabelNode ? (LabelNode)parent : null;
    }

    /**
     * Returns an iterator over all ancestors block of the given block, with its parent block first.
     */
    public Iterator<Block> getAncestorBlocks(Block block) {
        var iter = getBlocks();
        while (iter.hasNext()) {
            var b = iter.next();
            if (block == b) {
                return iter;
            }
        }
        throw new AssertionError("Block is not on the current lexical context stack");
    }

    /**
     * Returns an iterator over a block and all its ancestors blocks, with the block first.
     */
    public Iterator<Block> getBlocks(Block block) {
        var iter = getAncestorBlocks(block);
        return new Iterator<Block>() {
            boolean blockReturned = false;
            @Override
            public boolean hasNext() {
                return iter.hasNext() || !blockReturned;
            }
            @Override
            public Block next() {
                if (blockReturned) {
                    return iter.next();
                }
                blockReturned = true;
                return block;
            }
            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /**
     * Get the function for this block.
     */
    public FunctionNode getFunction(Block block) {
        var iter = new NodeIterator<>(LexicalContextNode.class);
        while (iter.hasNext()) {
            var next = iter.next();
            if (next == block) {
                while (iter.hasNext()) {
                    var next2 = iter.next();
                    if (next2 instanceof FunctionNode) {
                        return (FunctionNode)next2;
                    }
                }
            }
        }
        assert false; // should not be reached
        return null;
    }

    /**
     * Returns the innermost block in the context.
     */
    public Block getCurrentBlock() {
        return getBlocks().next();
    }

    /**
     * Returns the innermost function in the context.
     */
    public FunctionNode getCurrentFunction() {
        for (var i = sp - 1; i >= 0; i--) {
            if (stack[i] instanceof FunctionNode) {
                return (FunctionNode) stack[i];
            }
        }
        return null;
    }

    /**
     * Get the block in which a symbol is defined.
     */
    public Block getDefiningBlock(Symbol symbol) {
        var name = symbol.getName();
        for (var it = getBlocks(); it.hasNext();) {
            var next = it.next();
            if (next.getExistingSymbol(name) == symbol) {
                return next;
            }
        }
        throw new AssertionError("Couldn't find symbol " + name + " in the context");
    }

    /**
     * Get the function in which a symbol is defined.
     */
    public FunctionNode getDefiningFunction(Symbol symbol) {
        var name = symbol.getName();
        for (var iter = new NodeIterator<>(LexicalContextNode.class); iter.hasNext();) {
            var next = iter.next();
            if (next instanceof Block && ((Block)next).getExistingSymbol(name) == symbol) {
                while (iter.hasNext()) {
                    var next2 = iter.next();
                    if (next2 instanceof FunctionNode) {
                        return (FunctionNode)next2;
                    }
                }
                throw new AssertionError("Defining block for symbol " + name + " has no function in the context");
            }
        }
        throw new AssertionError("Couldn't find symbol " + name + " in the context");
    }

    /**
     * Is the topmost lexical context element a function body?
     */
    public boolean isFunctionBody() {
        return getParentBlock() == null;
    }

    /**
     * Is the topmost lexical context element body of a SplitNode?
     */
    public boolean isSplitBody() {
        return sp >= 2 && stack[sp - 1] instanceof Block && stack[sp - 2] instanceof SplitNode;
    }

    /**
     * Get the parent function for a function in the lexical context.
     */
    public FunctionNode getParentFunction(FunctionNode functionNode) {
        var iter = new NodeIterator<>(FunctionNode.class);
        while (iter.hasNext()) {
            var next = iter.next();
            if (next == functionNode) {
                return iter.hasNext() ? iter.next() : null;
            }
        }
        assert false;
        return null;
    }

    /**
     * Count the number of scopes until a given node.
     * Note that this method is solely used to figure out the number of scopes that need to be explicitly popped in order to perform a break or continue jump within the current bytecode method.
     * For this reason, the method returns 0 if it encounters a {@code SplitNode} between the current location and the break/continue target.
     */
    public int getScopeNestingLevelTo(LexicalContextNode until) {
        assert until != null;
        //count the number of with nodes until "until" is hit
        var n = 0;
        for (var iter = getAllNodes(); iter.hasNext();) {
            var node = iter.next();
            if (node == until) {
                break;
            }
            assert !(node instanceof FunctionNode); // Can't go outside current function
            if (node instanceof Block && ((Block)node).needsScope()) {
                n++;
            }
        }
        return n;
    }

    private BreakableNode getBreakable() {
        for (var iter = new NodeIterator<>(BreakableNode.class, getCurrentFunction()); iter.hasNext(); ) {
            var next = iter.next();
            if (next.isBreakableWithoutLabel()) {
                return next;
            }
        }
        return null;
    }

    /**
     * Check whether the lexical context is currently inside a loop.
     */
    public boolean inLoop() {
        return getCurrentLoop() != null;
    }

    /**
     * Returns the loop header of the current loop, or {@code null} if not inside a loop.
     */
    public LoopNode getCurrentLoop() {
        var iter = new NodeIterator<>(LoopNode.class, getCurrentFunction());
        return iter.hasNext() ? iter.next() : null;
    }

    /**
     * Find the breakable node corresponding to this label.
     * 'labelName' is the name of the label to search for.
     * If {@code null}, the closest breakable node will be returned unconditionally, e.g., a while loop with no label.
     */
    public BreakableNode getBreakable(String labelName) {
        if (labelName != null) {
            var foundLabel = findLabel(labelName);
            if (foundLabel != null) {
                // iterate to the nearest breakable to the foundLabel
                BreakableNode breakable = null;
                for (var iter = new NodeIterator<>(BreakableNode.class, foundLabel); iter.hasNext(); ) {
                    breakable = iter.next();
                }
                return breakable;
            }
            return null;
        }
        return getBreakable();
    }

    private LoopNode getContinueTo() {
        return getCurrentLoop();
    }

    /**
     * Find the continue target node corresponding to this label.
     * 'labelName' is the label name to search for.
     * If {@code null} the closest loop node will be returned unconditionally, e.g., a while loop with no label.
     */
    public LoopNode getContinueTo(String labelName) {
        if (labelName != null) {
            var foundLabel = findLabel(labelName);
            if (foundLabel != null) {
                // iterate to the nearest loop to the foundLabel
                LoopNode loop = null;
                for (var iter = new NodeIterator<>(LoopNode.class, foundLabel); iter.hasNext(); ) {
                    loop = iter.next();
                }
                return loop;
            }
            return null;
        }
        return getContinueTo();
    }

    /**
     * Find the inlined finally block node corresponding to this label.
     * 'labelName' is the label name to search for. Must not be {@code null}.
     * Returns the closest inlined finally block with the given label.
     */
    public Block getInlinedFinally(String labelName) {
        for (var iter = new NodeIterator<>(TryNode.class); iter.hasNext(); ) {
            var inlinedFinally = iter.next().getInlinedFinally(labelName);
            if (inlinedFinally != null) {
                return inlinedFinally;
            }
        }
        return null;
    }

    /**
     * Find the try node for an inlined finally block corresponding to this label.
     * 'labelName' is the label name to search for. Must not be {@code null}.
     * Returns the try node to which the labelled inlined finally block belongs.
     */
    public TryNode getTryNodeForInlinedFinally(String labelName) {
        for (var iter = new NodeIterator<>(TryNode.class); iter.hasNext(); ) {
            var tryNode = iter.next();
            if (tryNode.getInlinedFinally(labelName) != null) {
                return tryNode;
            }
        }
        return null;
    }

    /**
     * Check the lexical context for a given label node by name.
     */
    private LabelNode findLabel(String name) {
        for (var iter = new NodeIterator<>(LabelNode.class, getCurrentFunction()); iter.hasNext(); ) {
            var next = iter.next();
            if (next.getLabelName().equals(name)) {
                return next;
            }
        }
        return null;
    }

    /**
     * Checks whether a given target is a jump destination that lies outside a given split node.
     */
    public boolean isExternalTarget(SplitNode splitNode, BreakableNode target) {
        for (var i = sp; i-- > 0;) {
            var next = stack[i];
            if (next == splitNode) {
                return true;
            } else if (next == target) {
                return false;
            } else if (next instanceof TryNode) {
                for (var inlinedFinally : ((TryNode)next).getInlinedFinallies()) {
                    if (TryNode.getLabelledInlinedFinallyBlock(inlinedFinally) == target) {
                        return false;
                    }
                }
            }
        }
        throw new AssertionError(target + " was expected in lexical context " + LexicalContext.this + " but wasn't");
    }

    /**
     * Checks whether the current context is inside a switch statement without explicit blocks (curly braces).
     */
    public boolean inUnprotectedSwitchContext() {
        for (var i = sp - 1; i > 0; i--) {
            var next = stack[i];
            if (next instanceof Block) {
                return stack[i - 1] instanceof SwitchNode;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        var sb = new StringBuffer();
        sb.append("[ ");
        for (var i = 0; i < sp; i++) {
            var node = stack[i];
            sb.append(node.getClass().getSimpleName())
              .append('@')
              .append(Util.id(node))
              .append(':');
            if (node instanceof FunctionNode) {
                var fn = (FunctionNode)node;
                var source = fn.getSource();
                var src = source.toString();
                if (src.contains(File.pathSeparator)) {
                    src = src.substring(src.lastIndexOf(File.pathSeparator));
                }
                sb.append(src)
                  .append(' '+fn.getLineNumber());

            }
            sb.append(' ');
        }
        sb.append(" ==> ]");
        return sb.toString();
    }

    private class NodeIterator <T extends LexicalContextNode> implements Iterator<T> {
        private int index;
        private T next;
        private final Class<T> clazz;
        private LexicalContextNode until;

        NodeIterator(Class<T> clazz) {
            this(clazz, null);
        }

        NodeIterator(Class<T> clazz, LexicalContextNode until) {
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
