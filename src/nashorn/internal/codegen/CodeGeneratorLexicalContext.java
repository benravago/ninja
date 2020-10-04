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

package nashorn.internal.codegen;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import nashorn.internal.IntDeque;
import nashorn.internal.codegen.types.Type;
import nashorn.internal.ir.Block;
import nashorn.internal.ir.Expression;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.ir.LexicalContext;
import nashorn.internal.ir.LexicalContextNode;
import nashorn.internal.ir.Node;
import nashorn.internal.ir.Symbol;

/**
 * A lexical context that also tracks if we have any dynamic scopes in the context.
 *
 * Such scopes can have new variables introduced into them at run time - a with block or a function directly containing an eval call.
 * Furthermore, this class keeps track of current discard state, which the current method emitter being used is, the current compile unit, and local variable indexes
 */
final class CodeGeneratorLexicalContext extends LexicalContext {

    private int dynamicScopeCount;

    /** Map of shared scope call sites */
    private final Map<SharedScopeCall, SharedScopeCall> scopeCalls = new HashMap<>();

    /** Compile unit stack - every time we start a sub method (e.g. a split) we push one */
    private final Deque<CompileUnit> compileUnits = new ArrayDeque<>();

    /** Method emitter stack - every time we start a sub method (e.g. a split) we push one */
    private final Deque<MethodEmitter> methodEmitters = new ArrayDeque<>();

    /**
     * The discard stack - whenever we evaluate an expression that will be discarded, we push it on this stack.
     * Various implementations of expression code emitter can choose to emit code that'll discard the expression themselves, or ignore it in which case CodeGenerator.loadAndDiscard() will explicitly emit a pop instruction.
     */
    private final Deque<Expression> discard = new ArrayDeque<>();

    private final Deque<Map<String, Collection<Label>>> unwarrantedOptimismHandlers = new ArrayDeque<>();
    private final Deque<StringBuilder> slotTypesDescriptors = new ArrayDeque<>();
    private final IntDeque splitLiterals = new IntDeque();

    /**
     * A stack tracking the next free local variable slot in the blocks.
     * There's one entry for every block currently on the lexical context stack.
     */
    private int[] nextFreeSlots = new int[16];

    /** size of next free slot vector */
    private int nextFreeSlotsSize;

    @Override
    public <T extends LexicalContextNode> T push(T node) {
        if (node instanceof FunctionNode) {
            if (((FunctionNode)node).inDynamicContext()) {
                dynamicScopeCount++;
            }
            splitLiterals.push(0);
        }
        return super.push(node);
    }

    void enterSplitLiteral() {
        splitLiterals.getAndIncrement();
        pushFreeSlots(methodEmitters.peek().getUsedSlotsWithLiveTemporaries());
    }

    void exitSplitLiteral() {
        var count = splitLiterals.decrementAndGet();
        assert count >= 0;
    }

    @Override
    public <T extends Node> T pop(T node) {
        var popped = super.pop(node);
        if (node instanceof FunctionNode) {
            if (((FunctionNode)node).inDynamicContext()) {
                dynamicScopeCount--;
                assert dynamicScopeCount >= 0;
            }
            assert splitLiterals.peek() == 0;
            splitLiterals.pop();
        }
        return popped;
    }

    boolean inDynamicScope() {
        return dynamicScopeCount > 0;
    }

    boolean inSplitLiteral() {
        return !splitLiterals.isEmpty() && splitLiterals.peek() > 0;
    }

    MethodEmitter pushMethodEmitter(MethodEmitter newMethod) {
        methodEmitters.push(newMethod);
        return newMethod;
    }

    MethodEmitter popMethodEmitter(MethodEmitter oldMethod) {
        assert methodEmitters.peek() == oldMethod;
        methodEmitters.pop();
        return methodEmitters.isEmpty() ? null : methodEmitters.peek();
    }

    void pushUnwarrantedOptimismHandlers() {
        unwarrantedOptimismHandlers.push(new HashMap<String, Collection<Label>>());
        slotTypesDescriptors.push(new StringBuilder());
    }

    Map<String, Collection<Label>> getUnwarrantedOptimismHandlers() {
        return unwarrantedOptimismHandlers.peek();
    }

    Map<String, Collection<Label>> popUnwarrantedOptimismHandlers() {
        slotTypesDescriptors.pop();
        return unwarrantedOptimismHandlers.pop();
    }

    CompileUnit pushCompileUnit(CompileUnit newUnit) {
        compileUnits.push(newUnit);
        return newUnit;
    }

    CompileUnit popCompileUnit(CompileUnit oldUnit) {
        assert compileUnits.peek() == oldUnit;
        var unit = compileUnits.pop();
        assert unit.hasCode() : "compile unit popped without code";
        unit.setUsed();
        return compileUnits.isEmpty() ? null : compileUnits.peek();
    }

    boolean hasCompileUnits() {
        return !compileUnits.isEmpty();
    }

    Collection<SharedScopeCall> getScopeCalls() {
        return Collections.unmodifiableCollection(scopeCalls.values());
    }

    /**
     * Get a shared static method representing a dynamic scope callsite.
     * @param unit current compile unit
     * @param symbol the symbol
     * @param valueType the value type of the symbol
     * @param returnType the return type
     * @param paramTypes the parameter types
     * @param flags the callsite flags
     * @param isOptimistic is this an optimistic call
     * @return an object representing a shared scope call
     */
    SharedScopeCall getScopeCall(CompileUnit unit, Symbol symbol, Type valueType, Type returnType, Type[] paramTypes, int flags, boolean isOptimistic) {
        var scopeCall = new SharedScopeCall(symbol, valueType, returnType, paramTypes, flags, isOptimistic);
        if (scopeCalls.containsKey(scopeCall)) {
            return scopeCalls.get(scopeCall);
        }
        scopeCall.setClassAndName(unit, getCurrentFunction().uniqueName(":scopeCall"));
        scopeCalls.put(scopeCall, scopeCall);
        return scopeCall;
    }

    /**
     * Get a shared static method representing a dynamic scope get access.
     * @param unit current compile unit
     * @param symbol the symbol
     * @param valueType the type of the variable
     * @param flags the callsite flags
     * @param isOptimistic is this an optimistic get
     * @return an object representing a shared scope get
     */
    SharedScopeCall getScopeGet(CompileUnit unit, Symbol symbol, Type valueType, int flags, boolean isOptimistic) {
        return getScopeCall(unit, symbol, valueType, valueType, null, flags, isOptimistic);
    }

    void onEnterBlock(Block block) {
        pushFreeSlots(assignSlots(block, isFunctionBody() ? 0 : getUsedSlotCount()));
    }

    private void pushFreeSlots(int freeSlots) {
        if (nextFreeSlotsSize == nextFreeSlots.length) {
            var newNextFreeSlots = new int[nextFreeSlotsSize * 2];
            System.arraycopy(nextFreeSlots, 0, newNextFreeSlots, 0, nextFreeSlotsSize);
            nextFreeSlots = newNextFreeSlots;
        }
        nextFreeSlots[nextFreeSlotsSize++] = freeSlots;
    }

    int getUsedSlotCount() {
        return nextFreeSlots[nextFreeSlotsSize - 1];
    }

    void releaseSlots() {
        --nextFreeSlotsSize;
        var undefinedFromSlot = nextFreeSlotsSize == 0 ? 0 : nextFreeSlots[nextFreeSlotsSize - 1];
        if (!slotTypesDescriptors.isEmpty()) {
            slotTypesDescriptors.peek().setLength(undefinedFromSlot);
        }
        methodEmitters.peek().undefineLocalVariables(undefinedFromSlot, false);
    }

    private int assignSlots(Block block, int firstSlot) {
        var fromSlot = firstSlot;
        var method = methodEmitters.peek();
        for (var symbol : block.getSymbols()) {
            if (symbol.hasSlot()) {
                symbol.setFirstSlot(fromSlot);
                var toSlot = fromSlot + symbol.slotCount();
                method.defineBlockLocalVariable(fromSlot, toSlot);
                fromSlot = toSlot;
            }
        }
        return fromSlot;
    }

    static Type getTypeForSlotDescriptor(char typeDesc) {
        // Recognizing both lowercase and uppercase as we're using both to signify symbol boundaries; see MethodEmitter.markSymbolBoundariesInLvarTypesDescriptor().
        switch (typeDesc) {
            case 'I', 'i': return Type.INT;
            case 'J', 'j': return Type.LONG;
            case 'D', 'd': return Type.NUMBER;
            case 'A', 'a': return Type.OBJECT;
            case 'U', 'u': return Type.UNKNOWN;
            default:       throw new AssertionError();
        }
    }

    void pushDiscard(Expression expr) {
        discard.push(expr);
    }

    boolean popDiscardIfCurrent(Expression expr) {
        if (isCurrentDiscard(expr)) {
            discard.pop();
            return true;
        }
        return false;
    }

    boolean isCurrentDiscard(Expression expr) {
        return discard.peek() == expr;
    }

    int quickSlot(Type type) {
        return methodEmitters.peek().defineTemporaryLocalVariable(type.getSlots());
    }

}

