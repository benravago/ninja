/*
 * Copyright (c) 2010, 2016, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.invoke.MethodType;

import java.util.ArrayList;
import java.util.List;

import nashorn.internal.codegen.types.Type;
import nashorn.internal.ir.Expression;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.runtime.ScriptFunction;
import nashorn.internal.runtime.linker.LinkerCallSite;
import static nashorn.internal.lookup.Lookup.MH;

/**
 * Class that generates function signatures for dynamic calls
 */
public final class FunctionSignature {

    /** parameter types that ASM can understand */
    private final Type[] paramTypes;

    /** return type that ASM can understand */
    private final Type returnType;

    /** valid Java descriptor string for function */
    private final String descriptor;

    /** {@link MethodType} for function */
    private final MethodType methodType;

    /**
     * Constructor.
     * Create a FunctionSignature given arguments as AST Nodes
     * @param hasSelf   does the function have a self slot?
     * @param hasCallee does the function need a callee variable
     * @param retType   what is the return type
     * @param args      argument list of AST Nodes
     */
    public FunctionSignature(boolean hasSelf, boolean hasCallee, Type retType, List<? extends Expression> args) {
        this(hasSelf, hasCallee, retType, FunctionSignature.typeArray(args));
    }

    /**
     * Constructor.
     * Create a FunctionSignature given arguments as AST Nodes
     * @param hasSelf does the function have a self slot?
     * @param hasCallee does the function need a callee variable
     * @param retType what is the return type
     * @param nArgs   number of arguments
     */
    public FunctionSignature(boolean hasSelf, boolean hasCallee, Type retType, int nArgs) {
        this(hasSelf, hasCallee, retType, FunctionSignature.objectArgs(nArgs));
    }

    /**
     * Constructor.
     * Create a FunctionSignature given argument types only
     * @param hasSelf   does the function have a self slot?
     * @param hasCallee does the function have a callee slot?
     * @param retType   what is the return type
     * @param argTypes  argument list of AST Nodes
     */
    private FunctionSignature(boolean hasSelf, boolean hasCallee, Type retType, Type... argTypes) {
        boolean isVarArg;

        var count = 1;

        if (argTypes == null) {
            isVarArg = true;
        } else {
            isVarArg = argTypes.length > LinkerCallSite.ARGLIMIT;
            count    = isVarArg ? 1 : argTypes.length;
        }

        if (hasCallee) {
            count++;
        }
        if (hasSelf) {
            count++;
        }

        paramTypes = new Type[count];

        var next = 0;
        if (hasCallee) {
            paramTypes[next++] = Type.typeFor(ScriptFunction.class);
        }

        if (hasSelf) {
            paramTypes[next++] = Type.OBJECT;
        }

        if (isVarArg) {
            paramTypes[next] = Type.OBJECT_ARRAY;
        } else if (argTypes != null) {
            for (var j = 0; next < count;) {
                var type = argTypes[j++];
                // TODO: for now, turn java/lang/String into java/lang/Object as we aren't as specific.
                paramTypes[next++] = type.isObject() ? Type.OBJECT : type;
            }
        } else {
            assert false : "isVarArgs cannot be false when argTypes are null";
        }

        this.returnType = retType;
        this.descriptor = Type.getMethodDescriptor(returnType, paramTypes);

        var paramTypeList = new ArrayList<Class<?>>();
        for (var paramType : paramTypes) {
            paramTypeList.add(paramType.getTypeClass());
        }

        this.methodType = MH.type(returnType.getTypeClass(), paramTypeList.toArray(new Class<?>[0]));
    }

    /**
     * Create a function signature given a function node, using as much type information for parameters and return types that is available
     */
    public FunctionSignature(FunctionNode functionNode) {
        this(true, functionNode.needsCallee(), functionNode.getReturnType(), (functionNode.isVarArg() && !functionNode.isProgram()) ? null : functionNode.getParameters());
    }

    /**
     * Internal function that converts an array of nodes to their Types
     */
    private static Type[] typeArray(List<? extends Expression> args) {
        if (args == null) {
            return null;
        }

        var typeArray = new Type[args.size()];

        var pos = 0;
        for (var arg : args) {
            typeArray[pos++] = arg.getType();
        }

        return typeArray;
    }

    @Override
    public String toString() {
        return descriptor;
    }

    /**
     * @return the number of param types
     */
    public int size() {
        return paramTypes.length;
    }

    /**
     * Get the param types for this function signature
     */
    public Type[] getParamTypes() {
        return paramTypes.clone();
    }

    /**
     * Return the {@link MethodType} for this function signature
     */
    public MethodType getMethodType() {
        return methodType;
    }

    /**
     * Return the return type for this function signature
     */
    public Type getReturnType() {
        return returnType;
    }

    private static Type[] objectArgs(int nArgs) {
        var array = new Type[nArgs];
        for (var i = 0; i < nArgs; i++) {
            array[i] = Type.OBJECT;
        }
        return array;
    }

}
