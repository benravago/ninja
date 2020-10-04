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

package nashorn.internal.codegen.types;

import org.objectweb.asm.MethodVisitor;

/**
 * Numeric operations, not supported by all types
 */
interface BytecodeNumericOps {

    /**
     * Pop and negate the value on top of the stack and push the result
     */
    Type neg(MethodVisitor method, int programPoint);

    /**
     * Pop two values on top of the stack and subtract the first from the second, pushing the result on the stack
     */
    Type sub(MethodVisitor method, int programPoint);

    /**
     * Pop and multiply the two values on top of the stack and push the result on the stack
     */
    Type mul(MethodVisitor method, int programPoint);

    /**
     * Pop two values on top of the stack and divide the first with the second, pushing the result on the stack
     */
    Type div(MethodVisitor method, int programPoint);

    /**
     * Pop two values on top of the stack and compute the modulo of the first with the second, pushing the result on the stack.
     * Note that the rem method never takes a program point, because it can never be more optimistic than its widest operand - an int/int rem operation or a long/long rem operation can never return a wider remainder than the int or the long
     */
    Type rem(MethodVisitor method, int programPoint);

    /**
     * Comparison with int return value, e.g. LCMP, DCMP.
     */
    Type cmp(MethodVisitor method, boolean isCmpG);

}
