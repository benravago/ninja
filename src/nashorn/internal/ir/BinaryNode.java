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

import java.util.Set;

import nashorn.internal.codegen.types.Type;
import nashorn.internal.ir.annotations.Ignore;
import nashorn.internal.ir.annotations.Immutable;
import nashorn.internal.ir.visitor.NodeVisitor;
import nashorn.internal.parser.TokenType;
import static nashorn.internal.runtime.UnwarrantedOptimismException.INVALID_PROGRAM_POINT;

/**
 * BinaryNode nodes represent two operand operations.
 */
@Immutable
public final class BinaryNode extends Expression implements Assignment<Expression>, Optimistic {

    // Placeholder for "undecided optimistic ADD type".
    // Unfortunately, we can't decide the type of ADD during optimistic type calculation as it can have local variables as its operands that will decide its ultimate type.
    private static final Type OPTIMISTIC_UNDECIDED_TYPE = Type.typeFor(new Object(){/*empty*/}.getClass());

    /** Left hand side argument. */
    private final Expression lhs;

    private final Expression rhs;

    private final int programPoint;

    private final Type type;
    private transient Type cachedType;

    @Ignore
    private static final Set<TokenType> CAN_OVERFLOW = Set.of(
        TokenType.ADD,
        TokenType.DIV,
        TokenType.MOD,
        TokenType.MUL,
        TokenType.SUB,
        TokenType.ASSIGN_ADD,
        TokenType.ASSIGN_DIV,
        TokenType.ASSIGN_MOD,
        TokenType.ASSIGN_MUL,
        TokenType.ASSIGN_SUB,
        TokenType.SHR,
        TokenType.ASSIGN_SHR
    );

    /**
     * Constructor
     */
    public BinaryNode(long token, Expression lhs, Expression rhs) {
        super(token, lhs.getStart(), rhs.getFinish());
        assert !(isTokenType(TokenType.AND) || isTokenType(TokenType.OR)) || lhs instanceof JoinPredecessorExpression;
        this.lhs = lhs;
        this.rhs = rhs;
        this.programPoint = INVALID_PROGRAM_POINT;
        this.type = null;
    }

    private BinaryNode(BinaryNode binaryNode, Expression lhs, Expression rhs, Type type, int programPoint) {
        super(binaryNode);
        this.lhs = lhs;
        this.rhs = rhs;
        this.programPoint = programPoint;
        this.type = type;
    }

    /**
     * Returns true if the node is a comparison operation (either equality, inequality, or relational).
     */
    public boolean isComparison() {
        return switch (tokenType()) {
            case EQ, EQ_STRICT, NE, NE_STRICT, LE, LT, GE, GT -> true;
            default -> false;
        };
    }

    /**
     * Returns true if the node is a relational operation (less than (or equals), greater than (or equals)).
     */
    public boolean isRelational() {
        return switch (tokenType()) {
            case LT, GT, LE, GE -> true;
            default -> false;
        };
    }

    /**
     * Returns true if the node is a logical operation.
     */
    public boolean isLogical() {
        return isLogical(tokenType());
    }

    /**
     * Returns true if the token type represents a logical operation.
     */
    public static boolean isLogical(TokenType tokenType) {
        return switch (tokenType) {
            case AND, OR -> true;
            default -> false;
        };
    }

    /**
     * Return the widest possible operand type for this operation.
     */
    public Type getWidestOperandType() {
        switch (tokenType()) {
            case SHR, ASSIGN_SHR -> {
                return Type.INT;
            }
            case INSTANCEOF -> {
                return Type.OBJECT;
            }
            default ->  {
                if (isComparison()) {
                    return Type.OBJECT;
                }
                return getWidestOperationType();
            }
        }
    }

    @Override
    public Type getWidestOperationType() {
        switch (tokenType()) {
            case ADD, ASSIGN_ADD -> {
                // Compare this logic to decideType(Type, Type); it's similar, but it handles the optimistic type
                // calculation case while this handles the conservative case.
                var lhsType = lhs.getType();
                var rhsType = rhs.getType();
                if (lhsType == Type.BOOLEAN && rhsType == Type.BOOLEAN) {
                    // Will always fit in an int, as the value range is [0, 1, 2].
                    // If we didn't treat them specially here, they'd end up being treated as generic INT operands and their sum would be conservatively considered to be a LONG in the generic case below; we can do better here.
                    return Type.INT;
                } else if (isString(lhsType) || isString(rhsType)) {
                    // We can statically figure out that this is a string if either operand is a string.
                    // In this case, use CHARSEQUENCE to prevent it from being proactively flattened.
                    return Type.CHARSEQUENCE;
                }
                var widestOperandType = Type.widest(undefinedToNumber(booleanToInt(lhsType)), undefinedToNumber(booleanToInt(rhsType)));
                if (widestOperandType.isNumeric()) {
                    return Type.NUMBER;
                }
                // We pretty much can't know what it will be statically.
                // Must presume OBJECT conservatively, as we can end up getting either a string or an object when adding something + object,
                // e.g.: 1 + {} == "1[object Object]", but 1 + {valueOf: function() { return 2 }} == 3.
                // Also: 1 + { valueOf: function() { return "2" } } == "12".
                return Type.OBJECT;
            }
            case SHR, ASSIGN_SHR ->  {
                return Type.NUMBER;
            }
            case ASSIGN_SAR, ASSIGN_SHL, BIT_AND, BIT_OR, BIT_XOR, ASSIGN_BIT_AND, ASSIGN_BIT_OR, ASSIGN_BIT_XOR, SAR, SHL -> {
                return Type.INT;
            }
            case DIV, MOD, ASSIGN_DIV, ASSIGN_MOD -> {
                // Naively, one might think MOD has the same type as the widest of its operands, this is unfortunately not true when denominator is zero, so even type(int % int) == double.
                return Type.NUMBER;
            }
            case MUL, SUB, ASSIGN_MUL, ASSIGN_SUB -> {
                var lhsType = lhs.getType();
                var rhsType = rhs.getType();
                if (lhsType == Type.BOOLEAN && rhsType == Type.BOOLEAN) {
                    return Type.INT;
                }
                return Type.NUMBER;
            }
            case VOID -> {
                return Type.UNDEFINED;
            }
            case ASSIGN -> {
                return rhs.getType();
            }
            case INSTANCEOF -> {
                return Type.BOOLEAN;
            }
            case COMMARIGHT -> {
                return rhs.getType();
            }
            case AND, OR -> {
                return Type.widestReturnType(lhs.getType(), rhs.getType());
            }
            default ->  {
                if (isComparison()) {
                    return Type.BOOLEAN;
                }
                return Type.OBJECT;
            }
        }
    }

    private static boolean isString(Type type) {
        return type == Type.STRING || type == Type.CHARSEQUENCE;
    }

    private static Type booleanToInt(Type type) {
        return type == Type.BOOLEAN ? Type.INT : type;
    }

    private static Type undefinedToNumber(Type type) {
        return type == Type.UNDEFINED ? Type.NUMBER : type;
    }

    /**
     * Check if this node is an assignment
     */
    @Override
    public boolean isAssignment() {
        return switch (tokenType()) {
            case ASSIGN, ASSIGN_ADD, ASSIGN_BIT_AND, ASSIGN_BIT_OR, ASSIGN_BIT_XOR, ASSIGN_DIV, ASSIGN_MOD, ASSIGN_MUL, ASSIGN_SAR, ASSIGN_SHL, ASSIGN_SHR, ASSIGN_SUB -> true;
            default -> false;
        };
    }

    @Override
    public boolean isSelfModifying() {
        return isAssignment() && !isTokenType(TokenType.ASSIGN);
    }

    @Override
    public Expression getAssignmentDest() {
        return isAssignment() ? lhs() : null;
    }

    @Override
    public BinaryNode setAssignmentDest(Expression n) {
        return setLHS(n);
    }

    @Override
    public Expression getAssignmentSource() {
        return rhs();
    }

    /**
     * Assist in IR navigation.
     */
    @Override
    public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterBinaryNode(this)) {
            return visitor.leaveBinaryNode(setLHS((Expression)lhs.accept(visitor)).setRHS((Expression)rhs.accept(visitor)));
        }
        return this;
    }

    @Override
    public boolean isLocal() {
        return switch (tokenType()) {
            case SAR, SHL, SHR, BIT_AND, BIT_OR, BIT_XOR, ADD, DIV, MOD, MUL, SUB ->
                lhs.isLocal() && lhs.getType().isJSPrimitive() && rhs.isLocal() && rhs.getType().isJSPrimitive();
            case ASSIGN_ADD, ASSIGN_BIT_AND, ASSIGN_BIT_OR, ASSIGN_BIT_XOR, ASSIGN_DIV, ASSIGN_MOD, ASSIGN_MUL, ASSIGN_SAR, ASSIGN_SHL, ASSIGN_SHR, ASSIGN_SUB ->
                lhs instanceof IdentNode && lhs.isLocal() && lhs.getType().isJSPrimitive() && rhs.isLocal() && rhs.getType().isJSPrimitive();
            case ASSIGN ->
                lhs instanceof IdentNode && lhs.isLocal() && rhs.isLocal();
            default ->
                false;
        };
    }

    @Override
    public boolean isAlwaysFalse() {
        return switch (tokenType()) {
            case COMMARIGHT -> rhs.isAlwaysFalse();
            default -> false;
        };
    }

    @Override
    public boolean isAlwaysTrue() {
        return switch (tokenType()) {
            case COMMARIGHT -> rhs.isAlwaysTrue();
            default -> false;
        };
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        var tokenType = tokenType();

        var lhsParen = tokenType.needsParens(lhs().tokenType(), true);
        var rhsParen = tokenType.needsParens(rhs().tokenType(), false);

        if (lhsParen) {
            sb.append('(');
        }

        lhs().toString(sb, printType);

        if (lhsParen) {
            sb.append(')');
        }

        sb.append(' ');

        switch (tokenType) {
            case COMMARIGHT -> sb.append(",>");
            case INCPREFIX, DECPREFIX -> sb.append("++");
            default ->  sb.append(tokenType.getName());
        }

        if (isOptimistic()) {
            sb.append(Expression.OPT_IDENTIFIER);
        }

        sb.append(' ');

        if (rhsParen) {
            sb.append('(');
        }
        rhs().toString(sb, printType);
        if (rhsParen) {
            sb.append(')');
        }
    }

    /**
     * Get the left hand side expression for this node
     */
    public Expression lhs() {
        return lhs;
    }

    /**
     * Get the right hand side expression for this node
     */
    public Expression rhs() {
        return rhs;
    }

    /**
     * Set the left hand side expression for this node
     */
    public BinaryNode setLHS(Expression lhs) {
        if (this.lhs == lhs) {
            return this;
        }
        return new BinaryNode(this, lhs, rhs, type, programPoint);
    }

    /**
     * Set the right hand side expression for this node
     */
    public BinaryNode setRHS(Expression rhs) {
        if (this.rhs == rhs) {
            return this;
        }
        return new BinaryNode(this, lhs, rhs, type, programPoint);
    }

    /**
     * Set both the left and the right hand side expression for this node
     */
    public BinaryNode setOperands(Expression lhs, Expression rhs) {
        if (this.lhs == lhs && this.rhs == rhs) {
            return this;
        }
        return new BinaryNode(this, lhs, rhs, type, programPoint);
    }

    @Override
    public int getProgramPoint() {
        return programPoint;
    }

    @Override
    public boolean canBeOptimistic() {
        return isTokenType(TokenType.ADD) || (getMostOptimisticType() != getMostPessimisticType());
    }

    @Override
    public BinaryNode setProgramPoint(int programPoint) {
        if (this.programPoint == programPoint) {
            return this;
        }
        return new BinaryNode(this, lhs, rhs, type, programPoint);
    }

    @Override
    public Type getMostOptimisticType() {
        var tokenType = tokenType();
        if (tokenType == TokenType.ADD || tokenType == TokenType.ASSIGN_ADD) {
            return OPTIMISTIC_UNDECIDED_TYPE;
        } else if (CAN_OVERFLOW.contains(tokenType)) {
            return Type.INT;
        }
        return getMostPessimisticType();
    }

    @Override
    public Type getMostPessimisticType() {
        return getWidestOperationType();
    }

    /**
     * Returns true if the node has the optimistic type of the node is not yet decided.
     * Optimistic ADD nodes start out as undecided until we can figure out if they're numeric or not.
     */
    public boolean isOptimisticUndecidedType() {
        return type == OPTIMISTIC_UNDECIDED_TYPE;
    }

    @Override
    public Type getType() {
        if (cachedType == null) {
            cachedType = getTypeUncached();
        }
        return cachedType;
    }

    private Type getTypeUncached() {
        if (type == OPTIMISTIC_UNDECIDED_TYPE) {
            return decideType(lhs.getType(), rhs.getType());
        }
        var widest = getWidestOperationType();
        if (type == null) {
            return widest;
        }
        if (tokenType() == TokenType.ASSIGN_SHR || tokenType() == TokenType.SHR) {
            return type;
        }
        return Type.narrowest(widest, Type.widest(type, Type.widest(lhs.getType(), rhs.getType())));
    }

    private static Type decideType(Type lhsType, Type rhsType) {
        // Compare this to getWidestOperationType() for ADD and ASSIGN_ADD cases.
        // There's some similar logic, but these are optimistic decisions, meaning that we don't have to treat boolean addition separately (as it'll become int addition in the general case anyway), and that we also don't conservatively widen sums of ints to longs, or sums of longs to doubles.
        if (isString(lhsType) || isString(rhsType)) {
            return Type.CHARSEQUENCE;
        }
        // NOTE: We don't have optimistic object-to-(int, long) conversions.
        // Therefore, if any operand is an Object, we bail out of optimism here and presume a conservative Object return value, as the object's ToPrimitive() can end up returning either a number or a string, and their common supertype is Object, for better or worse.
        var widest = Type.widest(undefinedToNumber(booleanToInt(lhsType)), undefinedToNumber(booleanToInt(rhsType)));
        return widest.isObject() ? Type.OBJECT : widest;
    }

    /**
     * If the node is a node representing an add operation and has {@link #isOptimisticUndecidedType() optimistic undecided type}, decides its type.
     * Should be invoked after its operands types have been finalized.
     * Returns a new node similar to this node, but with its type set to the type decided from the type of its operands.
     */
    public BinaryNode decideType() {
        assert type == OPTIMISTIC_UNDECIDED_TYPE;
        return setType(decideType(lhs.getType(), rhs.getType()));
    }

    @Override
    public BinaryNode setType(Type type) {
        if (this.type == type) {
            return this;
        }
        return new BinaryNode(this, lhs, rhs, type, programPoint);
    }

}

