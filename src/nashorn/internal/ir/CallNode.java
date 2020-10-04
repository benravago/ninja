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

import static nashorn.internal.runtime.UnwarrantedOptimismException.INVALID_PROGRAM_POINT;

import java.io.Serializable;

import java.util.Collections;
import java.util.List;

import nashorn.internal.codegen.types.Type;
import nashorn.internal.ir.annotations.Ignore;
import nashorn.internal.ir.annotations.Immutable;
import nashorn.internal.ir.visitor.NodeVisitor;

/**
 * IR representation for a function call.
 */
@Immutable
public final class CallNode extends LexicalContextExpression implements Optimistic {

    /** Function identifier or function body. */
    private final Expression function;

    /** Call arguments. */
    private final List<Expression> args;

    /** Is this a "new" operation */
    private static final int IS_NEW = 1 << 0;

    /** Can this be a Function.call? */
    private static final int IS_APPLY_TO_CALL = 1 << 1;

    private final int flags;

    private final int lineNumber;

    private final int programPoint;

    private final Type optimisticType;

    /**
     * Arguments to be passed to builtin {@code eval} function
     */
    public static class EvalArgs implements Serializable {

        private final List<Expression> args;

        /** location string for the eval call */
        private final String location;

        /**
         * Constructor
         */
        public EvalArgs(List<Expression> args, String location) {
            this.args = args;
            this.location = location;
        }

        /**
         * Return the code that is to be eval'ed by this eval function
         */
        public List<Expression> getArgs() {
            return Collections.unmodifiableList(args);
        }

        private EvalArgs setArgs(List<Expression> args) {
            if (this.args == args) {
                return this;
            }
            return new EvalArgs(args, location);
        }

        /**
         * Get the human readable location for this eval call
         */
        public String getLocation() {
            return this.location;
        }
    }

    /** arguments for 'eval' call. Non-null only if this call node is 'eval' */
    @Ignore
    private final EvalArgs evalArgs;

    /**
     * Constructors
     */
    public CallNode(int lineNumber, long token, int finish, Expression function, List<Expression> args, boolean isNew) {
        super(token, finish);
        this.function = function;
        this.args = args;
        this.flags = isNew ? IS_NEW : 0;
        this.evalArgs = null;
        this.lineNumber = lineNumber;
        this.programPoint = INVALID_PROGRAM_POINT;
        this.optimisticType = null;
    }

    private CallNode(CallNode callNode, Expression function, List<Expression> args, int flags, Type optimisticType, EvalArgs evalArgs, int programPoint) {
        super(callNode);
        this.lineNumber = callNode.lineNumber;
        this.function = function;
        this.args = args;
        this.flags = flags;
        this.evalArgs = evalArgs;
        this.programPoint = programPoint;
        this.optimisticType = optimisticType;
    }

    /**
     * Returns the line number.
     */
    public int getLineNumber() {
        return lineNumber;
    }

    @Override
    public Type getType() {
        return optimisticType == null ? Type.OBJECT : optimisticType;
    }

    @Override
    public Optimistic setType(Type optimisticType) {
        if (this.optimisticType == optimisticType) {
            return this;
        }
        return new CallNode(this, function, args, flags, optimisticType, evalArgs, programPoint);
    }

    /**
     * Assist in IR navigation.
     */
    @Override
    public Node accept(LexicalContext lc, NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterCallNode(this)) {
            var newCallNode = (CallNode)visitor.leaveCallNode(
                setFunction((Expression)function.accept(visitor)).
                setArgs(Node.accept(visitor, args)).
                setEvalArgs(evalArgs == null ? null : evalArgs.setArgs(Node.accept(visitor, evalArgs.getArgs())))
            );
            // Theoretically, we'd need to instead pass lc to every setter and do a replacement on each.
            // In practice, setType from TypeOverride can't accept a lc, and we don't necessarily want to go there now.
            if (this != newCallNode) {
                return Node.replaceInLexicalContext(lc, this, newCallNode);
            }
        }
        return this;
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        if (printType) {
            optimisticTypeToString(sb);
        }

        var fsb = new StringBuilder();
        function.toString(fsb, printType);

        if (isApplyToCall()) {
            sb.append(fsb.toString().replace("apply", "[apply => call]"));
        } else {
            sb.append(fsb);
        }

        sb.append('(');

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
     * Get the arguments for the call
     */
    public List<Expression> getArgs() {
        return Collections.unmodifiableList(args);
    }

    /**
     * Reset the arguments for the call
     */
    public CallNode setArgs(List<Expression> args) {
        if (this.args == args) {
            return this;
        }
        return new CallNode(this, function, args, flags, optimisticType, evalArgs, programPoint);
    }

    /**
     * If this call is an {@code eval} call, get its EvalArgs structure
     */
    public EvalArgs getEvalArgs() {
        return evalArgs;
    }

    /**
     * Set the EvalArgs structure for this call, if it has been determined it is an {@code eval}
     */
    public CallNode setEvalArgs(EvalArgs evalArgs) {
        if (this.evalArgs == evalArgs) {
            return this;
        }
        return new CallNode(this, function, args, flags, optimisticType, evalArgs, programPoint);
    }

    /**
     * Check if this call is a call to {@code eval}
     */
    public boolean isEval() {
        return evalArgs != null;
    }

    /**
     * Is this an apply call that we optimistically should try to turn into a call instead
     */
    public boolean isApplyToCall() {
        return (flags & IS_APPLY_TO_CALL) != 0;
    }

    /**
     * Flag this call node as one that tries to call call instead of apply
     */
    public CallNode setIsApplyToCall() {
        return setFlags(flags | IS_APPLY_TO_CALL);
    }

    /**
     * Return the function expression that this call invokes
     */
    public Expression getFunction() {
        return function;
    }

    /**
     * Reset the function expression that this call invokes
     */
    public CallNode setFunction(Expression function) {
        if (this.function == function) {
            return this;
        }
        return new CallNode(this, function, args, flags, optimisticType, evalArgs, programPoint);
    }

    /**
     * Check if this call is a new operation
     */
    public boolean isNew() {
        return (flags & IS_NEW) != 0;
    }

    private CallNode setFlags(int flags) {
        if (this.flags == flags) {
            return this;
        }
        return new CallNode(this, function, args, flags, optimisticType, evalArgs, programPoint);
    }

    @Override
    public int getProgramPoint() {
        return programPoint;
    }

    @Override
    public CallNode setProgramPoint(int programPoint) {
        if (this.programPoint == programPoint) {
            return this;
        }
        return new CallNode(this, function, args, flags, optimisticType, evalArgs, programPoint);
    }

    @Override
    public Type getMostOptimisticType() {
        return Type.INT;
    }

    @Override
    public Type getMostPessimisticType() {
        return Type.OBJECT;
    }

    @Override
    public boolean canBeOptimistic() {
        return true;
    }

}
