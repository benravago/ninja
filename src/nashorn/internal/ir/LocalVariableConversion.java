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

/**
 * Class describing one or more local variable conversions that needs to be performed on entry to a control flow join point.
 *
 * Note that the class is named as a singular "Conversion" and not a plural "Conversions", but instances of the class have a reference to the next conversion, so multiple conversions are always represented with a single instance that is a head of a linked list of instances.
 * @see JoinPredecessor
 */
public final class LocalVariableConversion {

    private final Symbol symbol;

    // TODO: maybe introduce a type pair class? These will often be repeated.
    private final Type from;
    private final Type to;

    private final LocalVariableConversion next;

    /**
     * Creates a new object representing a local variable conversion.
     */
    public LocalVariableConversion(Symbol symbol, Type from, Type to, LocalVariableConversion next) {
        this.symbol = symbol; // the symbol representing the local variable whose value is being converted.
        this.from = from;     // the type value is being converted from.
        this.to = to;         // the type value is being converted to.
        this.next = next;     // next conversion at the same join point, if any (the conversion object implements a singly-linked list of conversions).
    }

    /**
     * Returns the type being converted from.
     */
    public Type getFrom() {
        return from;
    }

    /**
     * Returns the type being converted to.
     */
    public Type getTo() {
        return to;
    }

    /**
     * Returns the next conversion at the same join point, or null if this is the last one.
     */
    public LocalVariableConversion getNext() {
        return next;
    }

    /**
     * Returns the symbol representing the local variable whose value is being converted.
     */
    public Symbol getSymbol() {
        return symbol;
    }

    /**
     * Returns true if this conversion is live.
     * A conversion is live if the symbol has a slot for the conversion's {@link #getTo() to} type.
     * If a conversion is dead, it can be omitted in code generator.
     */
    public boolean isLive() {
        return symbol.hasSlotFor(to);
    }

    /**
     * Returns true if this conversion {@link #isLive()}, or if any of its {@link #getNext()} conversions are live.
     */
    public boolean isAnyLive() {
        return isLive() || isAnyLive(next);
    }

    /**
     * Returns true if the passed join predecessor has {@link #isAnyLive()} conversion.
     */
    public static boolean hasLiveConversion(JoinPredecessor jp) {
        return isAnyLive(jp.getLocalVariableConversion());
    }

    /**
     * Returns true if the passed conversion is not null, and it {@link #isAnyLive()}.
     */
    private static boolean isAnyLive(LocalVariableConversion conv) {
        return conv != null && conv.isAnyLive();
    }

    @Override
    public String toString() {
        return toString(new StringBuilder()).toString();
    }

    /**
     * Generates a string representation of this conversion in the passed string builder.
     */
    public StringBuilder toString(StringBuilder sb) {
        if (isLive()) {
            return toStringNext(sb.append('\u27e6'), true).append("\u27e7 ");
        }
        return next == null ? sb : next.toString(sb);
    }

    /**
     * Generates a string representation of the passed conversion in the passed string builder.
     */
    public static StringBuilder toString(LocalVariableConversion conv, StringBuilder sb) {
        return conv == null ? sb : conv.toString(sb);
    }

    private StringBuilder toStringNext(StringBuilder sb, boolean first) {
        if (isLive()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(symbol.getName()).append(':').append(getTypeChar(from)).append('\u2192').append(getTypeChar(to));
            return next == null ? sb : next.toStringNext(sb, false);
        }
        return next == null ? sb : next.toStringNext(sb, first);
    }

    private static char getTypeChar(Type type) {
        if (type == Type.UNDEFINED) {
            return 'U';
        } else if (type.isObject()) {
            return 'O';
        } else if (type == Type.BOOLEAN) {
            return 'Z';
        }
        return type.getBytecodeStackType();
    }

}
