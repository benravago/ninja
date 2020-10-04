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

import java.io.Serializable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import nashorn.internal.codegen.types.Type;

/**
 * Abstraction for labels, separating a label from the underlying byte code emitter.
 *
 * Also augmenting label with e.g. a name for easier debugging and reading code
 */
public final class Label implements Serializable {

    /**
     * Byte code generation evaluation type stack for consistency check and correct opcode selection.
     * One per label as a label may be a join point
     */
    static final class Stack implements Cloneable {

        static final int NON_LOAD = -1;

        Type[] data;
        int[]  localLoads;
        int    sp;

        List<Type> localVariableTypes;
        int firstTemp; // index of the first temporary local variable

        // Bitmap marking last slot belonging to a single symbol.
        BitSet symbolBoundary;

        Stack() {
            data = new Type[8];
            localLoads = new int[8];
            localVariableTypes = new ArrayList<>(8);
            symbolBoundary = new BitSet();
        }

        boolean isEmpty() {
            return sp == 0;
        }

        int size() {
            return sp;
        }

        void clear() {
            sp = 0;
        }

        void push(Type type) {
            if (data.length == sp) {
                var newData = new Type[sp * 2];
                var newLocalLoad = new int[sp * 2];
                System.arraycopy(data, 0, newData, 0, sp);
                System.arraycopy(localLoads, 0, newLocalLoad, 0, sp);
                data = newData;
                localLoads = newLocalLoad;
            }
            data[sp] = type;
            localLoads[sp] = NON_LOAD;
            sp++;
        }

        Type peek() {
            return peek(0);
        }

        Type peek(int n) {
            var pos = sp - 1 - n;
            return pos < 0 ? null : data[pos];
        }

        /**
         * Retrieve the top <code>count</code> types on the stack without modifying it.
         */
        Type[] getTopTypes(int count) {
            var topTypes = new Type[count];
            System.arraycopy(data, sp - count, topTypes, 0, count);
            return topTypes;
        }

        int[] getLocalLoads(int from, int to) {
            var count = to - from;
            var topLocalLoads = new int[count];
            System.arraycopy(localLoads, from, topLocalLoads, 0, count);
            return topLocalLoads;
        }

        /**
         * Returns the number of used local variable slots, including all live stack-store temporaries.
         */
        int getUsedSlotsWithLiveTemporaries() {
            // There are at least as many as are declared by the current blocks.
            var usedSlots = firstTemp;
            // Look at every load on the stack, and bump the number of used slots up by the temporaries seen there.
            for (var i = sp; i-->0;) {
                var slot = localLoads[i];
                if (slot != Label.Stack.NON_LOAD) {
                    var afterSlot = slot + localVariableTypes.get(slot).getSlots();
                    if (afterSlot > usedSlots) {
                        usedSlots = afterSlot;
                    }
                }
            }
            return usedSlots;
        }

        /**
         * @param joinOrigin the stack from the other branch.
         */
        void joinFrom(Stack joinOrigin, boolean breakTarget) {
            assert isStackCompatible(joinOrigin);
            if (breakTarget) {
                // As we're joining labels that can jump across block boundaries, the number of local variables can differ, and we should always respect the one having less variables.
                firstTemp = Math.min(firstTemp, joinOrigin.firstTemp);
            } else {
                assert firstTemp == joinOrigin.firstTemp;
            }
            var otherLoads = joinOrigin.localLoads;
            var firstDeadTemp = firstTemp;
            for (var i = 0; i < sp; ++i) {
                var localLoad = localLoads[i];
                if (localLoad != otherLoads[i]) {
                    localLoads[i] = NON_LOAD;
                } else if(localLoad >= firstDeadTemp) {
                    firstDeadTemp = localLoad + localVariableTypes.get(localLoad).getSlots();
                }
            }
            // Eliminate dead temporaries
            undefineLocalVariables(firstDeadTemp, false);
            assert isVariablePartitioningEqual(joinOrigin, firstDeadTemp);
            mergeVariableTypes(joinOrigin, firstDeadTemp);
        }

        private void mergeVariableTypes(Stack joinOrigin, int toSlot) {
            var it1 = localVariableTypes.listIterator();
            var it2 = joinOrigin.localVariableTypes.iterator();

            for (var i = 0; i < toSlot; ++i) {
                var thisType = it1.next();
                var otherType = it2.next();
                if (otherType == Type.UNKNOWN) {
                    // Variables that are <unknown> on the other branch will become <unknown> here too.
                    it1.set(Type.UNKNOWN);
                } else if (thisType != otherType) {
                    if (thisType.isObject() && otherType.isObject()) {
                        // Different object types are merged into Object.
                        // TODO: maybe find most common superclass?
                        it1.set(Type.OBJECT);
                    } else {
                        assert thisType == Type.UNKNOWN;
                    }
                }
            }
        }

        void joinFromTry(Stack joinOrigin) {
            // As we're joining labels that can jump across block boundaries, the number of local variables can differ, and we should always respect the one having less variables.
            firstTemp = Math.min(firstTemp, joinOrigin.firstTemp);
            assert isVariablePartitioningEqual(joinOrigin, firstTemp);
            mergeVariableTypes(joinOrigin, firstTemp);
        }

        private int getFirstDeadLocal(List<Type> types) {
            var i = types.size();
            for (var it = types.listIterator(i); it.hasPrevious() && it.previous() == Type.UNKNOWN; --i) {
                // no body
            }

            // Respect symbol boundaries; we never chop off half a symbol's storage
            while (!symbolBoundary.get(i - 1)) {
                ++i;
            }
            return i;
        }

        private boolean isStackCompatible(Stack other) {
            if (sp != other.sp) {
                return false;
            }
            for (var i = 0; i < sp; i++) {
                if (!data[i].isEquivalentTo(other.data[i])) {
                    return false;
                }
            }
            return true;
        }

        private boolean isVariablePartitioningEqual(Stack other, int toSlot) {
            // No difference in the symbol boundaries before the toSlot
            var diff = other.getSymbolBoundaryCopy();
            diff.xor(symbolBoundary);
            return diff.previousSetBit(toSlot - 1) == -1;
        }

        void markDeadLocalVariables(int fromSlot, int slotCount) {
            var localCount = localVariableTypes.size();
            if (fromSlot >= localCount) {
                return;
            }
            var toSlot = Math.min(fromSlot + slotCount, localCount);
            invalidateLocalLoadsOnStack(fromSlot, toSlot);
            for (var i = fromSlot; i < toSlot; ++i) {
                localVariableTypes.set(i, Type.UNKNOWN);
            }
        }

        @SuppressWarnings("unchecked")
        List<Type> getLocalVariableTypesCopy() {
            return (List<Type>)((ArrayList<Type>)localVariableTypes).clone();
        }

        BitSet getSymbolBoundaryCopy() {
            return (BitSet)symbolBoundary.clone();
        }

        /**
         * Returns a list of local variable slot types, but for those symbols that have multiple values, only the slot holding the widest type is marked as live.
         */
        List<Type> getWidestLiveLocals(List<Type> lvarTypes) {
            var widestLiveLocals = new ArrayList<Type>(lvarTypes);
            var keepNextValue = true;
            var size = widestLiveLocals.size();
            for (var i = size - 1; i-- > 0;) {
                if (symbolBoundary.get(i)) {
                    keepNextValue = true;
                }
                var t = widestLiveLocals.get(i);
                if (t != Type.UNKNOWN) {
                    if (keepNextValue) {
                        if (t != Type.SLOT_2) {
                            keepNextValue = false;
                        }
                    } else {
                        widestLiveLocals.set(i, Type.UNKNOWN);
                    }
                }
            }
            widestLiveLocals.subList(Math.max(getFirstDeadLocal(widestLiveLocals), firstTemp), widestLiveLocals.size()).clear();
            return widestLiveLocals;
        }

        String markSymbolBoundariesInLvarTypesDescriptor(String lvarDescriptor) {
            var chars = lvarDescriptor.toCharArray();
            var j = 0;
            for (var i = 0; i < chars.length; ++i) {
                var c = chars[i];
                var nextj = j + CodeGeneratorLexicalContext.getTypeForSlotDescriptor(c).getSlots();
                if (!symbolBoundary.get(nextj - 1)) {
                    chars[i] = Character.toLowerCase(c);
                }
                j = nextj;
            }
            return new String(chars);
        }

        Type pop() {
            assert sp > 0;
            return data[--sp];
        }

        @Override
        public Stack clone() {
            try {
                var clone = (Stack)super.clone();
                clone.data = data.clone();
                clone.localLoads = localLoads.clone();
                clone.symbolBoundary = getSymbolBoundaryCopy();
                clone.localVariableTypes = getLocalVariableTypesCopy();
                return clone;
            } catch(CloneNotSupportedException e) {
                throw new AssertionError("", e);
            }
        }

        private Stack cloneWithEmptyStack() {
            var stack = clone();
            stack.sp = 0;
            return stack;
        }

        int getTopLocalLoad() {
            return localLoads[sp - 1];
        }

        void markLocalLoad(int slot) {
            localLoads[sp - 1] = slot;
        }

        /**
         * Performs various bookeeping when a value is stored in a local variable slot.
         * @param slot the slot written to
         * @param onlySymbolLiveValue if true, this is the symbol's only live value, and other values of the symbol should be marked dead
         * @param type the type written to the slot
         */
        void onLocalStore(Type type, int slot, boolean onlySymbolLiveValue) {
            if (onlySymbolLiveValue) {
                var fromSlot = slot == 0 ? 0 : (symbolBoundary.previousSetBit(slot - 1) + 1);
                var toSlot = symbolBoundary.nextSetBit(slot) + 1;
                for (var i = fromSlot; i < toSlot; ++i) {
                    localVariableTypes.set(i, Type.UNKNOWN);
                }
                invalidateLocalLoadsOnStack(fromSlot, toSlot);
            } else {
                invalidateLocalLoadsOnStack(slot, slot + type.getSlots());
            }

            localVariableTypes.set(slot, type);
            if (type.isCategory2()) {
                localVariableTypes.set(slot + 1, Type.SLOT_2);
            }
        }

        /**
         * Given a slot range, invalidate knowledge about local loads on stack from these slots (because they're being killed).
         * @param fromSlot first slot, inclusive.
         * @param toSlot last slot, exclusive.
         */
        private void invalidateLocalLoadsOnStack(int fromSlot, int toSlot) {
            for (var i = 0; i < sp; ++i) {
                var localLoad = localLoads[i];
                if (localLoad >= fromSlot && localLoad < toSlot) {
                    localLoads[i] = NON_LOAD;
                }
            }
        }

        /**
         * Marks a range of slots as belonging to a defined local variable.
         * The slots will start out with no live value in them.
         * @param fromSlot first slot, inclusive.
         * @param toSlot last slot, exclusive.
         */
        void defineBlockLocalVariable(int fromSlot, int toSlot) {
            defineLocalVariable(fromSlot, toSlot);
            assert firstTemp < toSlot;
            firstTemp = toSlot;
        }

        /**
         * Defines a new temporary local variable and returns its allocated index.
         * @param width the required width (in slots) for the new variable.
         * @return the bytecode slot index where the newly allocated local begins.
         */
        int defineTemporaryLocalVariable(int width) {
            var fromSlot = getUsedSlotsWithLiveTemporaries();
            defineLocalVariable(fromSlot, fromSlot + width);
            return fromSlot;
        }

        /**
         * Marks a range of slots as belonging to a defined temporary local variable.
         * The slots will start out with no live value in them.
         */
        void defineTemporaryLocalVariable(int fromSlot, int toSlot) {
            defineLocalVariable(fromSlot, toSlot);
        }

        private void defineLocalVariable(int fromSlot, int toSlot) {
            assert !hasLoadsOnStack(fromSlot, toSlot);
            assert fromSlot < toSlot;
            symbolBoundary.clear(fromSlot, toSlot - 1);
            symbolBoundary.set(toSlot - 1);
            var lastExisting = Math.min(toSlot, localVariableTypes.size());
            for (var i = fromSlot; i < lastExisting; ++i) {
                localVariableTypes.set(i, Type.UNKNOWN);
            }
            for (var i = lastExisting; i < toSlot; ++i) {
                localVariableTypes.add(i, Type.UNKNOWN);
            }
        }

        /**
         * Undefines all local variables past the specified slot.
         * @param fromSlot the first slot to be undefined
         * @param canTruncateSymbol if false, the fromSlot must be either the first slot of a symbol, or the first slot after the last symbol.
         * If true, the fromSlot can be in the middle of the storage area for a symbol.
         * This should be used with care - it is only meant for use in optimism exception handlers.
         */
        void undefineLocalVariables(int fromSlot, boolean canTruncateSymbol) {
            var lvarCount = localVariableTypes.size();
            assert lvarCount == symbolBoundary.length();
            assert !hasLoadsOnStack(fromSlot, lvarCount);
            if (canTruncateSymbol) {
                if (fromSlot > 0) {
                    symbolBoundary.set(fromSlot - 1);
                }
            } else {
                assert fromSlot == 0 || symbolBoundary.get(fromSlot - 1);
            }
            if (fromSlot < lvarCount) {
                symbolBoundary.clear(fromSlot, lvarCount);
                localVariableTypes.subList(fromSlot, lvarCount).clear();
            }
            firstTemp = Math.min(fromSlot, firstTemp);
            assert symbolBoundary.length() == localVariableTypes.size();
            assert symbolBoundary.length() == fromSlot;
        }

        private void markAsOptimisticCatchHandler(int liveLocalCount) {
            // Live temporaries that are no longer on stack are undefined
            undefineLocalVariables(liveLocalCount, true);
            // Temporaries are promoted
            firstTemp = liveLocalCount;
            // No trailing undefined values
            localVariableTypes.subList(firstTemp, localVariableTypes.size()).clear();
            assert symbolBoundary.length() == firstTemp;
            // Generalize all reference types to Object, and promote boolean to int
            for (var it = localVariableTypes.listIterator(); it.hasNext();) {
                var type = it.next();
                if (type == Type.BOOLEAN) {
                    it.set(Type.INT);
                } else if (type.isObject() && type != Type.OBJECT) {
                    it.set(Type.OBJECT);
                }
            }
        }

        /**
         * Returns true if any loads on the stack come from the specified slot range.
         * @param fromSlot start of the range (inclusive)
         * @param toSlot end of the range (exclusive)
         * @return true if any loads on the stack come from the specified slot range.
         */
        boolean hasLoadsOnStack(int fromSlot, int toSlot) {
            for (var i = 0; i < sp; ++i) {
                var load = localLoads[i];
                if (load >= fromSlot && load < toSlot) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return "stack=" + Arrays.toString(Arrays.copyOf(data, sp))
                + ", symbolBoundaries=" + String.valueOf(symbolBoundary)
                + ", firstTemp=" + firstTemp
                + ", localTypes=" + String.valueOf(localVariableTypes)
                ;
        }
    }

    /** Next id for debugging purposes, remove if footprint becomes unmanageable */
    private static int nextId = 0;

    /** Name of this label */
    private final String name;

    /** Type stack at this label */
    private transient Label.Stack stack;

    /** ASM representation of this label */
    private transient org.objectweb.asm.Label label;

    /** Id for debugging purposes, remove if footprint becomes unmanageable */
    private final int id;

    /** Is this label reachable (anything ever jumped to it)? */
    private transient boolean reachable;

    private transient boolean breakTarget;

    /**
     * Constructor.
     * @param name name of this label
     */
    public Label(String name) {
        super();
        this.name = name;
        this.id = nextId++;
    }

    /**
     * Copy constructor
     * @param label a label to clone
     */
    public Label(Label label) {
        super();
        this.name = label.name;
        this.id = label.id;
    }

    org.objectweb.asm.Label getLabel() {
        if (this.label == null) {
            this.label = new org.objectweb.asm.Label();
        }
        return label;
    }

    Label.Stack getStack() {
        return stack;
    }

    void joinFrom(Label.Stack joinOrigin) {
        this.reachable = true;
        if (stack == null) {
            stack = joinOrigin.clone();
        } else {
            stack.joinFrom(joinOrigin, breakTarget);
        }
    }

    void joinFromTry(Label.Stack joinOrigin, boolean isOptimismHandler) {
        this.reachable = true;
        if (stack == null) {
            if (!isOptimismHandler) {
                stack = joinOrigin.cloneWithEmptyStack();
                // Optimism handler needs temporaries to remain live, others don't.
                stack.undefineLocalVariables(stack.firstTemp, false);
            }
        } else {
            assert !isOptimismHandler;
            stack.joinFromTry(joinOrigin);
        }
    }

    void markAsBreakTarget() {
        breakTarget = true;
    }

    boolean isBreakTarget() {
        return breakTarget;
    }

    void onCatch() {
        if (stack != null) {
            stack = stack.cloneWithEmptyStack();
        }
    }
    void markAsOptimisticCatchHandler(Label.Stack currentStack, int liveLocalCount) {
        stack = currentStack.cloneWithEmptyStack();
        stack.markAsOptimisticCatchHandler(liveLocalCount);
    }

    void markAsOptimisticContinuationHandlerFor(Label afterConsumeStackLabel) {
        stack = afterConsumeStackLabel.stack.cloneWithEmptyStack();
    }

    boolean isReachable() {
        return reachable;
    }

    boolean isAfter(Label other) {
        return label.getOffset() > other.label.getOffset();
    }

    private String str;

    @Override
    public String toString() {
        if (str == null) {
            str = name + '_' + id;
        }
        return str;
    }

}
