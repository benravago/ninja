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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nashorn.internal.codegen.types.Type;
import nashorn.internal.ir.annotations.Immutable;
import nashorn.internal.ir.visitor.NodeVisitor;
import nashorn.internal.parser.TokenType;

/**
 * IR representation for a runtime call.
 */
@Immutable
public class RuntimeNode extends Expression {

    /**
     * Request enum used for meta-information about the runtime request
     */
    public enum Request {
        /** An addition with at least one object */
        ADD(TokenType.ADD, Type.OBJECT, 2, true),
        /** Request to enter debugger */
        DEBUGGER,
        /** New operator */
        NEW,
        /** Typeof operator */
        TYPEOF,
        /** Reference error type */
        REFERENCE_ERROR,
        /** === operator with at least one object */
        EQ_STRICT(TokenType.EQ_STRICT, Type.BOOLEAN, 2, true),
        /** == operator with at least one object */
        EQ(TokenType.EQ, Type.BOOLEAN, 2, true),
        /** {@literal >=} operator with at least one object */
        GE(TokenType.GE, Type.BOOLEAN, 2, true),
        /** {@literal >} operator with at least one object */
        GT(TokenType.GT, Type.BOOLEAN, 2, true),
        /** in operator */
        IN(TokenType.IN, Type.BOOLEAN, 2),
        /** instanceof operator */
        INSTANCEOF(TokenType.INSTANCEOF, Type.BOOLEAN, 2),
        /** {@literal <=} operator with at least one object */
        LE(TokenType.LE, Type.BOOLEAN, 2, true),
        /** {@literal <} operator with at least one object */
        LT(TokenType.LT, Type.BOOLEAN, 2, true),
        /** !== operator with at least one object */
        NE_STRICT(TokenType.NE_STRICT, Type.BOOLEAN, 2, true),
        /** != operator with at least one object */
        NE(TokenType.NE, Type.BOOLEAN, 2, true),
        /** is undefined */
        IS_UNDEFINED(TokenType.EQ_STRICT, Type.BOOLEAN, 2),
        /** is not undefined */
        IS_NOT_UNDEFINED(TokenType.NE_STRICT, Type.BOOLEAN, 2),
        /** Get template object from raw and cooked string arrays. */
        GET_TEMPLATE_OBJECT(TokenType.TEMPLATE, Type.SCRIPT_OBJECT, 2);

        /** token type */
        private final TokenType tokenType;

        /** return type for request */
        private final Type returnType;

        /** arity of request */
        private final int arity;

        /** Can the specializer turn this into something that works with 1 or more primitives? */
        private final boolean canSpecialize;

        private Request() {
            this(TokenType.VOID, Type.OBJECT, 0);
        }

        private Request(TokenType tokenType, Type returnType, int arity) {
            this(tokenType, returnType, arity, false);
        }

        private Request(TokenType tokenType, Type returnType, int arity, boolean canSpecialize) {
            this.tokenType     = tokenType;
            this.returnType    = returnType;
            this.arity         = arity;
            this.canSpecialize = canSpecialize;
        }

        /**
         * Can this request type be specialized?
         */
        public boolean canSpecialize() {
            return canSpecialize;
        }

        /**
         * Get arity
         */
        public int getArity() {
            return arity;
        }

        /**
         * Get the return type
         */
        public Type getReturnType() {
            return returnType;
        }

        /**
         * Get token type
         */
        public TokenType getTokenType() {
            return tokenType;
        }

        /**
         * Get the non-strict name for this request.
         */
        public String nonStrictName() {
            return switch (this) {
                case NE_STRICT -> NE.name();
                case EQ_STRICT -> EQ.name();
                default -> name();
            };
        }

        /**
         * Derive a runtime node request type for a node
         */
        public static Request requestFor(Expression node) {
            switch (node.tokenType()) {
                case TYPEOF -> {
                    return Request.TYPEOF;
                }
                case IN -> {
                    return Request.IN;
                }
                case INSTANCEOF -> {
                    return Request.INSTANCEOF;
                }
                case EQ_STRICT -> {
                    return Request.EQ_STRICT;
                }
                case NE_STRICT -> {
                    return Request.NE_STRICT;
                }
                case EQ -> {
                    return Request.EQ;
                }
                case NE -> {
                    return Request.NE;
                }
                case LT -> {
                    return Request.LT;
                }
                case LE -> {
                    return Request.LE;
                }
                case GT -> {
                    return Request.GT;
                }
                case GE -> {
                    return Request.GE;
                }
                default -> {
                    assert false;
                    return null;
                }
            }
        }

        /**
         * Is this an undefined check?
         */
        public static boolean isUndefinedCheck(Request request) {
            return request == IS_UNDEFINED || request == IS_NOT_UNDEFINED;
        }

        /**
         * Is this an EQ or EQ_STRICT?
         */
        public static boolean isEQ(Request request) {
            return request == EQ || request == EQ_STRICT;
        }

        /**
         * Is this an NE or NE_STRICT?
         */
        public static boolean isNE(Request request) {
            return request == NE || request == NE_STRICT;
        }

        /**
         * Is this strict?
         */
        public static boolean isStrict(Request request) {
            return request == EQ_STRICT || request == NE_STRICT;
        }

        /**
         * If this request can be reversed, return the reverse request.
         * Eq EQ {@literal ->} NE.
         */
        public static Request reverse(Request request) {
            return switch (request) {
                case EQ, EQ_STRICT, NE, NE_STRICT -> request;
                case LE -> GE;
                case LT -> GT;
                case GE -> LE;
                case GT -> LT;
                default -> null;
            };
        }

        /**
         * Invert the request, only for non equals comparisons.
         */
        public static Request invert(Request request) {
            return switch (request) {
                case EQ -> NE;
                case EQ_STRICT -> NE_STRICT;
                case NE -> EQ;
                case NE_STRICT -> EQ_STRICT;
                case LE -> GT;
                case LT -> GE;
                case GE -> LT;
                case GT -> LE;
                default -> null;
            };
        }

        /**
         * Check if this is a comparison
         */
        public static boolean isComparison(Request request) {
            return switch (request) {
                case EQ, EQ_STRICT, NE, NE_STRICT, LE, LT, GE, GT, IS_UNDEFINED, IS_NOT_UNDEFINED -> true;
                default -> false;
            };
        }
    }

    /** Runtime request. */
    private final Request request;

    /** Call arguments. */
    private final List<Expression> args;

    /**
     * Constructor
     */
    public RuntimeNode(long token, int finish, Request request, List<Expression> args) {
        super(token, finish);
        this.request      = request;
        this.args         = args;
    }

    private RuntimeNode(RuntimeNode runtimeNode, Request request, List<Expression> args) {
        super(runtimeNode);
        this.request      = request;
        this.args         = args;
    }

    /**
     * Constructor
     */
    public RuntimeNode(long token, int finish, Request request, Expression... args) {
        this(token, finish, request, Arrays.asList(args));
    }

    /**
     * Constructor
     */
    public RuntimeNode(Expression parent, Request request, Expression... args) {
        this(parent, request, Arrays.asList(args));
    }

    /**
     * Constructor
     */
    public RuntimeNode(Expression parent, Request request, List<Expression> args) {
        super(parent);
        this.request      = request;
        this.args         = args;
    }

    /**
     * Constructor
     */
    public RuntimeNode(UnaryNode parent, Request request) {
        this(parent, request, parent.getExpression());
    }

    /**
     * Constructor used to replace a binary node with a runtime request.
     */
    public RuntimeNode(BinaryNode parent) {
        this(parent, Request.requestFor(parent), parent.lhs(), parent.rhs());
    }

    /**
     * Reset the request for this runtime node
     */
    public RuntimeNode setRequest(Request request) {
        if (this.request == request) {
            return this;
        }
        return new RuntimeNode(this, request, args);
   }

    /**
     * Return type for the ReferenceNode
     */
    @Override
    public Type getType() {
        return request.getReturnType();
    }

    @Override
    public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterRuntimeNode(this)) {
            return visitor.leaveRuntimeNode(setArgs(Node.accept(visitor, args)));
        }
        return this;
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        sb.append("ScriptRuntime.")
          .append(request)
          .append('(');

        var first = true;

        for (var arg : args) {
            if (!first) {
                sb.append(", ");
            } else {
                first = false;
            }

            arg.toString(sb, printType);
        }

        sb.append(')');
    }

    /**
     * Get the arguments for this runtime node
     */
    public List<Expression> getArgs() {
        return Collections.unmodifiableList(args);
    }

    /**
     * Set the arguments of this runtime node
     */
    public RuntimeNode setArgs(List<Expression> args) {
        if (this.args == args) {
            return this;
        }
        return new RuntimeNode(this, request, args);
    }

    /**
     * Get the request that this runtime node implements
     */
    public Request getRequest() {
        return request;
    }

    /**
     * Is this runtime node, engineered to handle the "at least one object" case of the defined requests and specialize on demand, really primitive.
     * This can happen e.g. after AccessSpecializer.
     * In that case it can be turned into a simpler primitive form in CodeGenerator.
     */
    public boolean isPrimitive() {
        for (var arg : args) {
            if (arg.getType().isObject()) {
                return false;
            }
        }
        return true;
    }

}
