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
package nashorn.internal.parser;

import java.util.ArrayList;
import java.util.List;
import nashorn.internal.ir.Statement;

/**
 * Base class for parser context nodes
 */
abstract class ParserContextBaseNode implements ParserContextNode {

    /**
     * Flags for this node
     */
    protected int flags;

    private List<Statement> statements;

    /**
     * Constructor
     */
    public ParserContextBaseNode() {
        this.statements = new ArrayList<>();
    }

    @Override
    public int getFlags() {
        return flags;
    }

    /**
     * Returns a single flag
     */
    protected int getFlag(int flag) {
        return (flags & flag);
    }

    /**
     * Sets a single flag
     */
    @Override
    public int setFlag(int flag) {
        flags |= flag;
        return flags;
    }

    @Override
    public List<Statement> getStatements() {
        return statements;
    }

    @Override
    public void setStatements(List<Statement> statements) {
        this.statements = statements;
    }

    /**
     * Adds a statement at the end of the statement list
     */
    @Override
    public void appendStatement(Statement statement) {
        this.statements.add(statement);
    }

    /**
     * Adds a statement at the beginning of the statement list
     */
    @Override
    public void prependStatement(Statement statement) {
        this.statements.add(0, statement);
    }

}
