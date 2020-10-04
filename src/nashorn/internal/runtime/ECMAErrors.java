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

package nashorn.internal.runtime;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;
import nashorn.internal.codegen.CompilerConstants;
import nashorn.internal.objects.Global;
import nashorn.internal.scripts.JS;

/**
 * Helper class to throw various standard "ECMA error" exceptions such as Error, ReferenceError, TypeError etc.
 */
public final class ECMAErrors {
    private ECMAErrors() {}

    private static final String MESSAGES_RESOURCE = "nashorn.internal.runtime.resources.Messages";

    private static final ResourceBundle MESSAGES_BUNDLE;
    static {
        MESSAGES_BUNDLE = ResourceBundle.getBundle(MESSAGES_RESOURCE, Locale.getDefault());
    }

    /** We assume that compiler generates script classes into the known package. */
    private static final String scriptPackage;
    static {
        var name = JS.class.getName();
        scriptPackage = name.substring(0, name.lastIndexOf('.'));
    }

    private static ECMAException error(Object thrown, Throwable cause) {
        return new ECMAException(thrown, cause);
    }

     /**
     * Error dispatch mechanism.
     * Create a {@link ParserException} as the correct JavaScript error
     * @param e {@code ParserException} for error dispatcher
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException asEcmaException(ParserException e) {
        return asEcmaException(Context.getGlobal(), e);
    }

    /**
     * Error dispatch mechanism.
     * Create a {@link ParserException} as the correct JavaScript error
     * @param global global scope object
     * @param e {@code ParserException} for error dispatcher
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException asEcmaException(Global global, ParserException e) {
        var errorType = e.getErrorType();
        assert errorType != null : "error type for " + e + " was null";

        var globalObj = global;
        var msg = e.getMessage();

        // translate to ECMAScript Error object using error type
        var e_msg = switch (errorType) {
            case ERROR -> globalObj.newError(msg);
            case EVAL_ERROR -> globalObj.newEvalError(msg);
            case RANGE_ERROR -> globalObj.newRangeError(msg);
            case REFERENCE_ERROR -> globalObj.newReferenceError(msg);
            case SYNTAX_ERROR -> globalObj.newSyntaxError(msg);
            case TYPE_ERROR -> globalObj.newTypeError(msg);
            case URI_ERROR -> globalObj.newURIError(msg);
            // should not happen - perhaps unknown error type?
            default -> throw new AssertionError(e.getMessage());
        };
        return error(e_msg, e);
    }

    /**
     * Create a syntax error (ECMA 15.11.6.4)
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException syntaxError(String msgId, String... args) {
        return syntaxError(Context.getGlobal(), msgId, args);
    }

    /**
     * Create a syntax error (ECMA 15.11.6.4)
     * @param global  global scope object
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException syntaxError(Global global, String msgId, String... args) {
        return syntaxError(global, null, msgId, args);
    }

    /**
     * Create a syntax error (ECMA 15.11.6.4)
     * @param cause   native Java {@code Throwable} that is the cause of error
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException syntaxError(Throwable cause, String msgId, String... args) {
        return syntaxError(Context.getGlobal(), cause, msgId, args);
    }

    /**
     * Create a syntax error (ECMA 15.11.6.4)
     * @param global  global scope object
     * @param cause   native Java {@code Throwable} that is the cause of error
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException syntaxError(Global global, Throwable cause, String msgId, String... args) {
        var msg = getMessage("syntax.error." + msgId, args);
        return error(global.newSyntaxError(msg), cause);
    }

    /**
     * Create a type error (ECMA 15.11.6.5)
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException typeError(String msgId, String... args) {
        return typeError(Context.getGlobal(), msgId, args);
    }

    /**
     * Create a type error (ECMA 15.11.6.5)
     * @param global  global scope object
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException typeError(Global global, String msgId, String... args) {
        return typeError(global, null, msgId, args);
    }

    /**
     * Create a type error (ECMA 15.11.6.5)
     * @param cause   native Java {@code Throwable} that is the cause of error
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException typeError(Throwable cause, String msgId, String... args) {
        return typeError(Context.getGlobal(), cause, msgId, args);
    }

    /**
     * Create a type error (ECMA 15.11.6.5)
     * @param global  global scope object
     * @param cause   native Java {@code Throwable} that is the cause of error
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException typeError(Global global, Throwable cause, String msgId, String... args) {
        var msg = getMessage("type.error." + msgId, args);
        return error(global.newTypeError(msg), cause);
    }

    /**
     * Create a range error (ECMA 15.11.6.2)
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException rangeError(String msgId, String... args) {
        return rangeError(Context.getGlobal(), msgId, args);
    }

    /**
     * Create a range error (ECMA 15.11.6.2)
     * @param global  global scope object
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException rangeError(Global global, String msgId, String... args) {
        return rangeError(global, null, msgId, args);
    }

    /**
     * Create a range error (ECMA 15.11.6.2)
     * @param cause   native Java {@code Throwable} that is the cause of error
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException rangeError(Throwable cause, String msgId, String... args) {
        return rangeError(Context.getGlobal(), cause, msgId, args);
    }

    /**
     * Create a range error (ECMA 15.11.6.2)
     * @param global  global scope object
     * @param cause   native Java {@code Throwable} that is the cause of error
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException rangeError(Global global, Throwable cause, String msgId, String... args) {
        var msg = getMessage("range.error." + msgId, args);
        return error(global.newRangeError(msg), cause);
    }

    /**
     * Create a reference error (ECMA 15.11.6.3)
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException referenceError(String msgId, String... args) {
        return referenceError(Context.getGlobal(), msgId, args);
    }

    /**
     * Create a reference error (ECMA 15.11.6.3)
     * @param global  global scope object
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     *
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException referenceError(Global global, String msgId, String... args) {
        return referenceError(global, null, msgId, args);
    }

    /**
     * Create a reference error (ECMA 15.11.6.3)
     * @param cause   native Java {@code Throwable} that is the cause of error
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException referenceError(Throwable cause, String msgId, String... args) {
        return referenceError(Context.getGlobal(), cause, msgId, args);
    }

    /**
     * Create a reference error (ECMA 15.11.6.3)
     * @param global  global scope object
     * @param cause   native Java {@code Throwable} that is the cause of error
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException referenceError(Global global, Throwable cause, String msgId, String... args) {
        var msg = getMessage("reference.error." + msgId, args);
        return error(global.newReferenceError(msg), cause);
    }

    /**
     * Create a URI error (ECMA 15.11.6.6)
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException uriError(String msgId, String... args) {
        return uriError(Context.getGlobal(), msgId, args);
    }

    /**
     * Create a URI error (ECMA 15.11.6.6)
     * @param global  global scope object
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException uriError(Global global, String msgId, String... args) {
        return uriError(global, null, msgId, args);
    }

    /**
     * Create a URI error (ECMA 15.11.6.6)
     * @param cause   native Java {@code Throwable} that is the cause of error
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException uriError(Throwable cause, String msgId, String... args) {
        return uriError(Context.getGlobal(), cause, msgId, args);
    }

    /**
     * Create a URI error (ECMA 15.11.6.6)
     * @param global  global scope object
     * @param cause   native Java {@code Throwable} that is the cause of error
     * @param msgId   resource tag for error message
     * @param args    arguments to resource
     * @return the resulting {@link ECMAException}
     */
    public static ECMAException uriError(Global global, Throwable cause, String msgId, String... args) {
        var msg = getMessage("uri.error." + msgId, args);
        return error(global.newURIError(msg), cause);
    }

    /**
     * Get the exception message by placing the args in the resource defined by the resource tag.
     * This is visible to, e.g. the {@link nashorn.internal.parser.Parser} can use it to generate compile time messages with the correct locale
     * @param msgId the resource tag (message id)
     * @param args  arguments to error string
     * @return the filled out error string
     */
    public static String getMessage(String msgId, String... args) {
        try {
            return new MessageFormat(MESSAGES_BUNDLE.getString(msgId)).format(args);
        } catch (java.util.MissingResourceException e) {
            throw new IllegalArgumentException("no message resource found for message id: "+ msgId);
        }
    }

    /**
     * Check if a stack trace element is in JavaScript
     */
    public static boolean isScriptFrame(StackTraceElement frame) {
        var className = frame.getClassName();

        // Look for script package in class name (into which compiler puts generated code)
        if (className.startsWith(scriptPackage) && !CompilerConstants.isInternalMethodName(frame.getMethodName())) {
            var source = frame.getFileName();
            // Make sure that it is not some Java code that Nashorn has in that package!
            return source != null && !source.endsWith(".java");
        }
        return false;
    }

}
