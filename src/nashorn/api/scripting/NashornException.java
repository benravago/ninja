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

package nashorn.api.scripting;

import java.util.ArrayList;

import nashorn.internal.codegen.CompilerConstants;
import nashorn.internal.runtime.ECMAErrors;
import nashorn.internal.runtime.ScriptObject;

/**
 * This is base exception for all Nashorn exceptions.
 *
 * These originate from user's ECMAScript code.
 * Example: script parse errors, exceptions thrown from scripts.
 * Note that ScriptEngine methods like "eval", "invokeMethod", "invokeFunction" will wrap this as ScriptException and throw it.
 * But, there are cases where user may need to access this exception (or implementation defined subtype of this).
 * For example, if java interface is implemented by a script object or Java access to script object properties via java.util.Map interface.
 * In these cases, user code will get an instance of this or implementation defined subclass.
 *
 * @since 1.8u40
 */
public abstract class NashornException extends RuntimeException {

    // script file name
    private String fileName;
    // script line number
    private int line;
    // are the line and fileName unknown?
    private boolean lineAndFileNameUnknown;
    // script column number
    private int column;
    // underlying ECMA error object - lazily initialized
    private Object ecmaError;

    /**
     * Constructor to initialize error message, file name, line and column numbers.
     */
    protected NashornException(String msg, String fileName, int line, int column) {
        this(msg, null, fileName, line, column);
    }

    /**
     * Constructor to initialize error message, cause exception, file name, line and column numbers.
     */
    protected NashornException(String msg, Throwable cause, String fileName, int line, int column) {
        super(msg, cause == null ? null : cause);
        this.fileName = fileName;
        this.line = line;
        this.column = column;
    }

    /**
     * Constructor to initialize error message and cause exception.
     */
    protected NashornException(String msg, Throwable cause) {
        super(msg, cause == null ? null : cause);
        // Hard luck - no column number info
        this.column = -1;
        // We can retrieve the line number and file name from the stack trace if needed
        this.lineAndFileNameUnknown = true;
    }

    /**
     * Get the source file name for this {@code NashornException}
     */
    public final String getFileName() {
        ensureLineAndFileName();
        return fileName;
    }

    /**
     * Set the source file name for this {@code NashornException}
     */
    public final void setFileName(String fileName) {
        this.fileName = fileName;
        lineAndFileNameUnknown = false;
    }

    /**
     * Get the line number for this {@code NashornException}
     */
    public final int getLineNumber() {
        ensureLineAndFileName();
        return line;
    }

    /**
     * Set the line number for this {@code NashornException}
     */
    public final void setLineNumber(int line) {
        lineAndFileNameUnknown = false;
        this.line = line;
    }

    /**
     * Get the column for this {@code NashornException}
     */
    public final int getColumnNumber() {
        return column;
    }

    /**
     * Set the column for this {@code NashornException}
     */
    public final void setColumnNumber(int column) {
        this.column = column;
    }

    /**
     * Returns array javascript stack frames from the given exception object.
     */
    public static StackTraceElement[] getScriptFrames(Throwable exception) {
        var frames = exception.getStackTrace();
        var filtered = new ArrayList<StackTraceElement>();
        for (var st : frames) {
            if (ECMAErrors.isScriptFrame(st)) {
                var className = "<" + st.getFileName() + ">";
                var methodName = st.getMethodName();
                if (methodName.equals(CompilerConstants.PROGRAM.symbolName())) {
                    methodName = "<program>";
                } else {
                    methodName = stripMethodName(methodName);
                }
                filtered.add(new StackTraceElement(className, methodName, st.getFileName(), st.getLineNumber()));
            }
        }
        return filtered.toArray(new StackTraceElement[0]);
    }

    private static String stripMethodName(String methodName) {
        var name = methodName;

        var nestedSeparator = name.lastIndexOf(CompilerConstants.NESTED_FUNCTION_SEPARATOR.symbolName());
        if (nestedSeparator >= 0) {
            name = name.substring(nestedSeparator + 1);
        }

        var idSeparator = name.indexOf(CompilerConstants.ID_FUNCTION_SEPARATOR.symbolName());
        if (idSeparator >= 0) {
            name = name.substring(0, idSeparator);
        }

        return name.contains(CompilerConstants.ANON_FUNCTION_PREFIX.symbolName()) ? "<anonymous>" : name;
    }

    /**
     * Return a formatted script stack trace string with frames information separated by '\n'
     */
    public static String getScriptStackString(Throwable exception) {
        var buf = new StringBuilder();
        var frames = getScriptFrames(exception);
        for (var st : frames) {
            buf.append("\tat ")
               .append(st.getMethodName())
               .append(" (")
               .append(st.getFileName())
               .append(':')
               .append(st.getLineNumber())
               .append(")\n");
        }
        var len = buf.length();
        // remove trailing '\n'
        if (len > 0) {
            assert buf.charAt(len - 1) == '\n';
            buf.deleteCharAt(len - 1);
        }
        return buf.toString();
    }

    /**
     * Get the thrown object. Subclass responsibility
     */
    protected Object getThrown() {
        return null;
    }

    /**
     * Initialization function for ECMA errors.
     * Stores the error in the ecmaError field of this class.
     * It is only initialized once, and then reused
     */
    NashornException initEcmaError(ScriptObject global) {
        if (ecmaError != null) {
            return this; // initialized already!
        }
        var thrown = getThrown();
        if (thrown instanceof ScriptObject) {
            setEcmaError(ScriptObjectMirror.wrap(thrown, global));
        } else {
            setEcmaError(thrown);
        }
        return this;
    }

    /**
     * Return the lying ECMA Error object's mirror, if available, or whatever was thrown from script such as a String, Number or a Boolean.
     */
    public Object getEcmaError() {
        return ecmaError;
    }

    /**
     * Set the underlying ECMA error object.
     */
    public void setEcmaError(Object ecmaError) {
        this.ecmaError = ecmaError;
    }

    private void ensureLineAndFileName() {
        if (lineAndFileNameUnknown) {
            for (var ste : getStackTrace()) {
                if (ECMAErrors.isScriptFrame(ste)) {
                    // Whatever here is compiled from JavaScript code
                    fileName = ste.getFileName();
                    line = ste.getLineNumber();
                    return;
                }
            }
            lineAndFileNameUnknown = false;
        }
    }

}
