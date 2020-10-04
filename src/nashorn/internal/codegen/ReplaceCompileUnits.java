/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

import java.util.ArrayList;

import nashorn.internal.ir.CompileUnitHolder;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.ir.LiteralNode;
import nashorn.internal.ir.LiteralNode.ArrayLiteralNode;
import nashorn.internal.ir.Node;
import nashorn.internal.ir.ObjectNode;
import nashorn.internal.ir.Splittable;
import nashorn.internal.ir.visitor.SimpleNodeVisitor;

/**
 * Base class for a node visitor that replaces {@link CompileUnit}s in {@link CompileUnitHolder}s.
 */
abstract class ReplaceCompileUnits extends SimpleNodeVisitor {

    /**
     * Override to provide a replacement for an old compile unit.
     */
    abstract CompileUnit getReplacement(CompileUnit oldUnit);

    CompileUnit getExistingReplacement(CompileUnitHolder node) {
        var oldUnit = node.getCompileUnit();
        assert oldUnit != null;

        var newUnit = getReplacement(oldUnit);
        assert newUnit != null;

        return newUnit;
    }

    @Override
    public Node leaveFunctionNode(FunctionNode node) {
        return node.setCompileUnit(lc, getExistingReplacement(node));
    }

    @Override
    public Node leaveLiteralNode(LiteralNode<?> node) {
        if (node instanceof ArrayLiteralNode) {
            var aln = (ArrayLiteralNode)node;
            if (aln.getSplitRanges() == null) {
                return node;
            }
            var newArrayUnits = new ArrayList<Splittable.SplitRange>();
            for (var au : aln.getSplitRanges()) {
                newArrayUnits.add(new Splittable.SplitRange(getExistingReplacement(au), au.getLow(), au.getHigh()));
            }
            return aln.setSplitRanges(lc, newArrayUnits);
        }
        return node;
    }

    @Override
    public Node leaveObjectNode(ObjectNode objectNode) {
        var ranges = objectNode.getSplitRanges();
        if (ranges != null) {
            var newRanges = new ArrayList<Splittable.SplitRange>();
            for (var range : ranges) {
                newRanges.add(new Splittable.SplitRange(getExistingReplacement(range), range.getLow(), range.getHigh()));
            }
            return objectNode.setSplitRanges(lc, newRanges);
        }
        return super.leaveObjectNode(objectNode);
    }

}
