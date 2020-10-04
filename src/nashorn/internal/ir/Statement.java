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

/**
 * Statement is something that becomes code and can be stepped past.
 *
 * A block is made up of statements.
 * The only node subclass that needs to keep token and location information is the Statement
 */
public abstract class Statement extends Node implements Terminal {

    private final int lineNumber;

    /**
     * Constructor
     */
    public Statement(int lineNumber, long token, int finish) {
        super(token, finish);
        this.lineNumber = lineNumber;
    }

    /**
     * Constructor
     */
    protected Statement(int lineNumber, long token, int start, int finish) {
        super(token, start, finish);
        this.lineNumber = lineNumber;
    }

    /**
     * Copy constructor
     */
    protected Statement(Statement node) {
        super(node);
        this.lineNumber = node.lineNumber;
    }

    /**
     * Return the line number
     */
    public int getLineNumber() {
        return lineNumber;
    }

    /**
     * Is this a terminal statement, i.e. does it end control flow like a throw or return?
     */
    @Override
    public boolean isTerminal() {
        return false;
    }

    /**
     * Check if this statement repositions control flow with goto like semantics, for example {@link BreakNode} or a {@link ForNode} with no test
     */
    public boolean hasGoto() {
        return false;
    }

    /**
     * Check if this statement has terminal flags, i.e. ends or breaks control flow
     */
    public final boolean hasTerminalFlags() {
        return isTerminal() || hasGoto();
    }

}

