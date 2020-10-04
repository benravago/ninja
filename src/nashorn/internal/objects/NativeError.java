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

package nashorn.internal.objects;

import static nashorn.internal.lookup.Lookup.MH;
import static nashorn.internal.runtime.ScriptRuntime.UNDEFINED;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import nashorn.api.scripting.NashornException;
import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.Property;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.objects.annotations.Where;
import nashorn.internal.runtime.ECMAException;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptFunction;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;

/**
 * ECMA 15.11 Error Objects
 */
@ScriptClass("Error")
public final class NativeError extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    static final MethodHandle GET_COLUMNNUMBER = findOwnMH("getColumnNumber", Object.class, Object.class);
    static final MethodHandle SET_COLUMNNUMBER = findOwnMH("setColumnNumber", Object.class, Object.class, Object.class);
    static final MethodHandle GET_LINENUMBER   = findOwnMH("getLineNumber", Object.class, Object.class);
    static final MethodHandle SET_LINENUMBER   = findOwnMH("setLineNumber", Object.class, Object.class, Object.class);
    static final MethodHandle GET_FILENAME     = findOwnMH("getFileName", Object.class, Object.class);
    static final MethodHandle SET_FILENAME     = findOwnMH("setFileName", Object.class, Object.class, Object.class);
    static final MethodHandle GET_STACK        = findOwnMH("getStack", Object.class, Object.class);
    static final MethodHandle SET_STACK        = findOwnMH("setStack", Object.class, Object.class, Object.class);

    // message property name
    static final String MESSAGE = "message";
    // name property name
    static final String NAME = "name";
    // stack property name
    static final String STACK = "__stack__";
    // lineNumber property name
    static final String LINENUMBER = "__lineNumber__";
    // columnNumber property name
    static final String COLUMNNUMBER = "__columnNumber__";
    // fileName property name
    static final String FILENAME = "__fileName__";

    /** Message property name */
    @Property(name = NativeError.MESSAGE, attributes = Attribute.NOT_ENUMERABLE)
    public Object instMessage;

    /** ECMA 15.11.4.2 Error.prototype.name */
    @Property(attributes = Attribute.NOT_ENUMERABLE, where = Where.PROTOTYPE)
    public Object name;

    /** ECMA 15.11.4.3 Error.prototype.message */
    @Property(attributes = Attribute.NOT_ENUMERABLE, where = Where.PROTOTYPE)
    public Object message;

    /** Nashorn extension: underlying exception */
    @Property(attributes = Attribute.NOT_ENUMERABLE)
    public Object nashornException;

    @SuppressWarnings("LeakingThisInConstructor")
    private NativeError(Object msg, ScriptObject proto, PropertyMap map) {
        super(proto, map);
        if (msg != UNDEFINED) {
            this.instMessage = JSType.toString(msg);
        } else {
            this.delete(NativeError.MESSAGE); // false
        }
        initException(this);
    }

    NativeError(Object msg, Global global) {
        this(msg, global.getErrorPrototype(), $nasgenmap$);
    }

    private NativeError(Object msg) {
        this(msg, Global.instance());
    }

    @Override
    public String getClassName() {
        return "Error";
    }

    /**
     * ECMA 15.11.2 The Error Constructor
     */
    @Constructor
    public static NativeError constructor(boolean newObj, Object self, Object msg) {
        return new NativeError(msg);
    }

    // This is called NativeError, NativeTypeError etc. to associate a ECMAException with the ECMA Error object.
    static void initException(ScriptObject self) {
        // ECMAException constructor has side effects
        new ECMAException(self, null);
    }

    /**
     * Nashorn extension: Error.captureStackTrace. Capture stack trace at the point of call into the Error object provided.
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static Object captureStackTrace(Object self, Object errorObj) {
        var sobj = Global.checkObject(errorObj);
        initException(sobj);
        sobj.delete(STACK); // false
        if (! sobj.has("stack")) {
            var getStack = ScriptFunction.createBuiltin("getStack", GET_STACK);
            var setStack = ScriptFunction.createBuiltin("setStack", SET_STACK);
            sobj.addOwnProperty("stack", Attribute.NOT_ENUMERABLE, getStack, setStack);
        }
        return UNDEFINED;
    }

    /**
     * Nashorn extension: Error.dumpStack.
     * Dumps the stack of the current thread.
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static Object dumpStack(Object self) {
        Thread.dumpStack();
        return UNDEFINED;
    }

    /**
     * Nashorn extension: Error.prototype.printStackTrace.
     * Prints stack trace associated with the exception (if available) to the standard error stream.
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object printStackTrace(Object self) {
        return ECMAException.printStackTrace(Global.checkObject(self));
    }

    /**
     * Nashorn extension: Error.prototype.getStackTrace().
     * "stack" property is an array typed value containing {@link StackTraceElement} objects of JavaScript stack frames.
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object getStackTrace(Object self) {
        var sobj = Global.checkObject(self);
        var exception = ECMAException.getException(sobj);
        Object[] res;
        if (exception instanceof Throwable) {
            res = NashornException.getScriptFrames((Throwable)exception);
        } else {
            res = ScriptRuntime.EMPTY_ARRAY;
        }
        return new NativeArray(res);
    }

    /**
     * Nashorn extension: Error.prototype.lineNumber
     */
    public static Object getLineNumber(Object self) {
        var sobj = Global.checkObject(self);
        return sobj.has(LINENUMBER) ? sobj.get(LINENUMBER) : ECMAException.getLineNumber(sobj);
    }

    /**
     * Nashorn extension: Error.prototype.lineNumber
     */
    public static Object setLineNumber(Object self, Object value) {
        var sobj = Global.checkObject(self);
        if (sobj.hasOwnProperty(LINENUMBER)) {
            sobj.put(LINENUMBER, value); // false
        } else {
            sobj.addOwnProperty(LINENUMBER, Attribute.NOT_ENUMERABLE, value);
        }
        return value;
    }

    /**
     * Nashorn extension: Error.prototype.columnNumber
     */
    public static Object getColumnNumber(Object self) {
        var sobj = Global.checkObject(self);
        return sobj.has(COLUMNNUMBER) ? sobj.get(COLUMNNUMBER) : ECMAException.getColumnNumber((ScriptObject)self);
    }

    /**
     * Nashorn extension: Error.prototype.columnNumber
     */
    public static Object setColumnNumber(Object self, Object value) {
        var sobj = Global.checkObject(self);
        if (sobj.hasOwnProperty(COLUMNNUMBER)) {
            sobj.put(COLUMNNUMBER, value); // false
        } else {
            sobj.addOwnProperty(COLUMNNUMBER, Attribute.NOT_ENUMERABLE, value);
        }
        return value;
    }

    /**
     * Nashorn extension: Error.prototype.fileName
     */
    public static Object getFileName(Object self) {
        var sobj = Global.checkObject(self);
        return sobj.has(FILENAME) ? sobj.get(FILENAME) : ECMAException.getFileName((ScriptObject)self);
    }

    /**
     * Nashorn extension: Error.prototype.fileName
     */
    public static Object setFileName(Object self, Object value) {
        var sobj = Global.checkObject(self);
        if (sobj.hasOwnProperty(FILENAME)) {
            sobj.put(FILENAME, value); // false
        } else {
            sobj.addOwnProperty(FILENAME, Attribute.NOT_ENUMERABLE, value);
        }
        return value;
    }

    /**
     * Nashorn extension: Error.prototype.stack.
     * "stack" property is a string typed value containing JavaScript stack frames.
     * Each frame information is separated bv "\n" character.
     */
    public static Object getStack(Object self) {
        var sobj = Global.checkObject(self);
        if (sobj.has(STACK)) {
            return sobj.get(STACK);
        }

        var exception = ECMAException.getException(sobj);
        if (exception instanceof Throwable) {
            var value = getScriptStackString(sobj, (Throwable)exception);
            if (sobj.hasOwnProperty(STACK)) {
                sobj.put(STACK, value); // false
            } else {
                sobj.addOwnProperty(STACK, Attribute.NOT_ENUMERABLE, value);
            }
            return value;
        }

        return UNDEFINED;
    }

    /**
     * Nashorn extension.
     * Accessed from {@link Global} while setting up the Error.prototype
     */
    public static Object setStack(Object self, Object value) {
        var sobj = Global.checkObject(self);
        if (sobj.hasOwnProperty(STACK)) {
            sobj.put(STACK, value); // false
        } else {
            sobj.addOwnProperty(STACK, Attribute.NOT_ENUMERABLE, value);
        }
        return value;
    }

    /**
     * ECMA 15.11.4.4 Error.prototype.toString ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object toString(Object self) {

        // Step 1 and 2 : check if 'self' is object it not throw TypeError
        var sobj = Global.checkObject(self);

        // Step 3 & 4 : get "name" and convert to String.
        // But if message is undefined make it "Error".
        var name = sobj.get("name");
        if (name == UNDEFINED) {
            name = "Error";
        } else {
            name = JSType.toString(name);
        }

        // Steps 5, 6, & 7 : get "message" and convert to String.
        // if 'message' is undefined make it "" (empty String).
        var msg = sobj.get("message");
        if (msg == UNDEFINED) {
            msg = "";
        } else {
            msg = JSType.toString(msg);
        }

        // Step 8 : if name is empty, return msg
        if (((String)name).isEmpty()) {
            return msg;
        }

        // Step 9 : if message is empty, return name
        if (((String)msg).isEmpty()) {
            return name;
        }

        // Step 10 : return name + ": " + msg
        return name + ": " + msg;
    }

    private static MethodHandle findOwnMH(String name, Class<?> rtype, Class<?>... types) {
        return MH.findStatic(MethodHandles.lookup(), NativeError.class, name, MH.type(rtype, types));
    }

    private static String getScriptStackString(ScriptObject sobj, Throwable exp) {
        return JSType.toString(sobj) + "\n" + NashornException.getScriptStackString(exp);
    }

}
