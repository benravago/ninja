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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import nashorn.internal.codegen.types.Type;

/**
 * Lexical context that keeps track of optimistic assumptions (if any) made during code generation.
 *
 * Used from Attr and FinalizeTypes
 */
public class OptimisticLexicalContext extends LexicalContext {

    private final boolean isEnabled;

    class Assumption {
        Symbol symbol;
        Type   type;

        Assumption(Symbol symbol, Type type) {
            this.symbol = symbol;
            this.type   = type;
        }
        @Override
        public String toString() {
            return symbol.getName() + "=" + type;
        }
    }

    /** Optimistic assumptions that could be made per function */
    private final Deque<List<Assumption>> optimisticAssumptions = new ArrayDeque<>();

    /**
     * Constructor.
     */
    public OptimisticLexicalContext(boolean isEnabled) {
        super();
        this.isEnabled = isEnabled;
    }

    /**
     * Are optimistic types enabled?
     */
    public boolean isEnabled() {
        return isEnabled;
    }

    /**
     * Log an optimistic assumption during codegen.
     * TODO : different parameters and more info about the assumption for future profiling needs
     */
    public void logOptimisticAssumption(Symbol symbol, Type type) {
        if (isEnabled) {
            var peek = optimisticAssumptions.peek();
            peek.add(new Assumption(symbol, type));
        }
    }

    /**
     * Get the list of optimistic assumptions made
     */
    List<Assumption> getOptimisticAssumptions() {
        return Collections.unmodifiableList(optimisticAssumptions.peek());
    }

    /**
     * Does this method have optimistic assumptions made during codegen?
     */
    public boolean hasOptimisticAssumptions() {
        return !optimisticAssumptions.isEmpty() && !getOptimisticAssumptions().isEmpty();
    }

    @Override
    public <T extends LexicalContextNode> T push(T node) {
        if (isEnabled) {
            if (node instanceof FunctionNode) {
                optimisticAssumptions.push(new ArrayList<>());
            }
        }
        return super.push(node);
    }

    @Override
    public <T extends Node> T pop(T node) {
        var popped = super.pop(node);
        if (isEnabled) {
            if (node instanceof FunctionNode) {
                optimisticAssumptions.pop();
            }
        }
        return popped;
    }

}
