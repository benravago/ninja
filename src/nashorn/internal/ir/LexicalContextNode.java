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

import nashorn.internal.ir.visitor.NodeVisitor;

/**
 * Interface for nodes that can be part of the lexical context.
 * @see LexicalContext
 */
public interface LexicalContextNode {

    /**
     * Accept function for the node given a lexical context.
     * It must be prepared to replace itself if present in the lexical context
     */
    Node accept(LexicalContext lc, NodeVisitor<? extends LexicalContext> visitor);

    /**
     * Helper class for accept for items of this lexical context, delegates to the subclass accept and makes sure that the node is on the context before accepting and gets popped after accepting (and that the stack is consistent in that the node has been replaced with the possible new node resulting in visitation).
     * Would be a default method on Java 8.
     */
    static class Acceptor {
        static Node accept(LexicalContextNode node, NodeVisitor<? extends LexicalContext> visitor) {
            var lc = visitor.getLexicalContext();
            lc.push(node);
            var newNode = node.accept(lc, visitor);
            return lc.pop(newNode);
        }
    }

}
