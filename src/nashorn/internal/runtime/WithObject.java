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

import static nashorn.internal.lookup.Lookup.MH;
import static nashorn.internal.runtime.ScriptRuntime.UNDEFINED;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SwitchPoint;
import jdk.dynalink.CallSiteDescriptor;
import jdk.dynalink.NamedOperation;
import jdk.dynalink.Operation;
import jdk.dynalink.StandardOperation;
import jdk.dynalink.linker.GuardedInvocation;
import jdk.dynalink.linker.LinkRequest;
import nashorn.api.scripting.AbstractJSObject;
import nashorn.api.scripting.ScriptObjectMirror;
import nashorn.internal.runtime.linker.NashornCallSiteDescriptor;
import nashorn.internal.runtime.linker.NashornGuards;

/**
 * This class supports the handling of scope in a with body.
 */
public final class WithObject extends Scope {

    private static final MethodHandle WITHEXPRESSIONGUARD    = findOwnMH("withExpressionGuard",  boolean.class, Object.class, PropertyMap.class, SwitchPoint[].class);
    private static final MethodHandle WITHEXPRESSIONFILTER   = findOwnMH("withFilterExpression", Object.class, Object.class);
    private static final MethodHandle WITHSCOPEFILTER        = findOwnMH("withFilterScope",      Object.class, Object.class);
    private static final MethodHandle BIND_TO_EXPRESSION_OBJ = findOwnMH("bindToExpression",     Object.class, Object.class, Object.class);
    private static final MethodHandle BIND_TO_EXPRESSION_FN  = findOwnMH("bindToExpression",     Object.class, ScriptFunction.class, Object.class);

    /** With expression object. */
    private final ScriptObject expression;

    /**
     * Constructor
     * @param scope scope object
     * @param expression with expression
     */
    WithObject(ScriptObject scope, ScriptObject expression) {
        super(scope, null);
        this.expression = expression;
        setIsInternal();
    }

    /**
     * Delete a property based on a key.
     * @param key Any valid JavaScript value.
     * @return True if deleted.
     */
    @Override
    public boolean delete(Object key) {
        var self = expression;
        var propName = JSType.toString(key);

        var find = self.findProperty(propName, true);

        if (find != null) {
            return self.delete(propName);
        }

        return false;
    }


    @Override
    public GuardedInvocation lookup(CallSiteDescriptor desc, LinkRequest request) {
        if (request.isCallSiteUnstable()) {
            // Fall back to megamorphic invocation which performs a complete lookup each time without further relinking.
            return super.lookup(desc, request);
        }

        GuardedInvocation link = null;
        var op = desc.getOperation();

        assert op instanceof NamedOperation; // WithObject is a scope object, access is always named
        var name = ((NamedOperation)op).getName().toString();

        var find = expression.findProperty(name, true);

        if (find != null) {
            link = expression.lookup(desc, request);
            if (link != null) {
                return fixExpressionCallSite(desc, link);
            }
        }

        var scope = getProto();
        find = scope.findProperty(name, true);

        if (find != null) {
            return fixScopeCallSite(scope.lookup(desc, request), name, find.getOwner());
        }

        // the property is not found - now check for __noSuchProperty__ and __noSuchMethod__ in expression
        String fallBack;

        var firstOp = NashornCallSiteDescriptor.getBaseOperation(desc);
        if (firstOp == StandardOperation.GET) {
            if (NashornCallSiteDescriptor.isMethodFirstOperation(desc)) {
                fallBack = NO_SUCH_METHOD_NAME;
            } else {
                fallBack = NO_SUCH_PROPERTY_NAME;
            }
        } else {
            fallBack = null;
        }

        if (fallBack != null) {
            find = expression.findProperty(fallBack, true);
            if (find != null) {
                if (NO_SUCH_METHOD_NAME.equals(fallBack)) {
                    link = expression.noSuchMethod(desc, request).addSwitchPoint(getProtoSwitchPoint(name));
                } else if (NO_SUCH_PROPERTY_NAME.equals(fallBack)) {
                    link = expression.noSuchProperty(desc, request).addSwitchPoint(getProtoSwitchPoint(name));
                }
            }
        }

        if (link != null) {
            return fixExpressionCallSite(desc, link);
        }

        // still not found, may be scope can handle with it's own __noSuchProperty__, __noSuchMethod__ etc.
        link = scope.lookup(desc, request);

        if (link != null) {
            return fixScopeCallSite(link, name, null);
        }

        return null;
    }

    /**
     * Overridden to try to find the property first in the expression object (and its prototypes), and only then in this object (and its prototypes).
     * @param key  Property key.
     * @param deep Whether the search should look up proto chain.
     * @param isScope true if is this a scope access
     * @param start the object on which the lookup was originally initiated
     * @return FindPropertyData or null if not found.
     */
    @Override
    protected FindProperty findProperty(Object key, boolean deep, boolean isScope, ScriptObject start) {
        // We call findProperty on 'expression' with 'expression' itself as start parameter.
        // This way in ScriptObject.setObject we can tell the property is from a 'with' expression (as opposed from another non-scope object in the proto chain such as Object.prototype).
        var exprProperty = expression.findProperty(key, true, false, expression);
        if (exprProperty != null) {
            return exprProperty;
        }
        return super.findProperty(key, deep, isScope, start);
    }

    @Override
    protected Object invokeNoSuchProperty(Object key, boolean isScope, int programPoint) {
        var find = expression.findProperty(NO_SUCH_PROPERTY_NAME, true);
        if (find != null) {
            var func = find.getObjectValue();
            if (func instanceof ScriptFunction) {
                var sfunc = (ScriptFunction)func;
                var self = isScope ? UNDEFINED : expression;
                return ScriptRuntime.apply(sfunc, self, key);
            }
        }

        return getProto().invokeNoSuchProperty(key, isScope, programPoint);
    }

    @Override
    public void setSplitState(int state) {
        ((Scope) getNonWithParent()).setSplitState(state);
    }

    @Override
    public int getSplitState() {
        return ((Scope) getNonWithParent()).getSplitState();
    }

    @Override
    public void addBoundProperties(ScriptObject source, Property[] properties) {
        // Declared variables in nested eval go to first normal (non-with) parent scope.
        getNonWithParent().addBoundProperties(source, properties);
    }

    /**
     * Get first parent scope that is not an instance of WithObject.
     */
    private ScriptObject getNonWithParent() {
        var proto = getProto();

        while (proto != null && proto instanceof WithObject) {
            proto = proto.getProto();
        }

        return proto;
    }

    private static GuardedInvocation fixReceiverType(GuardedInvocation link, MethodHandle filter) {
        // The receiver may be an Object or a ScriptObject.
        var invType = link.getInvocation().type();
        var newInvType = invType.changeParameterType(0, filter.type().returnType());
        return link.asType(newInvType);
    }

    private static GuardedInvocation fixExpressionCallSite(CallSiteDescriptor desc, GuardedInvocation link) {
        // If it's not a getMethod, just add an expression filter that converts WithObject in "this" position to its expression.
        if (NashornCallSiteDescriptor.getBaseOperation(desc) != StandardOperation.GET || !NashornCallSiteDescriptor.isMethodFirstOperation(desc)) {
            return fixReceiverType(link, WITHEXPRESSIONFILTER).filterArguments(0, WITHEXPRESSIONFILTER);
        }

        var linkInvocation = link.getInvocation();
        var linkType = linkInvocation.type();
        var linkReturnsFunction = ScriptFunction.class.isAssignableFrom(linkType.returnType());

        return link.replaceMethods(
            // Make sure getMethod will bind the script functions it receives to WithObject.expression
            MH.foldArguments(
                linkReturnsFunction ? BIND_TO_EXPRESSION_FN : BIND_TO_EXPRESSION_OBJ,
                filterReceiver(linkInvocation.asType(
                    linkType.changeReturnType(linkReturnsFunction ? ScriptFunction.class : Object.class)
                            .changeParameterType(0, Object.class)),
                    WITHEXPRESSIONFILTER)),
                    filterGuardReceiver(link, WITHEXPRESSIONFILTER)
        );
        // No clever things for the guard -- it is still identically filtered.
    }

    private GuardedInvocation fixScopeCallSite(GuardedInvocation link, String name, ScriptObject owner) {
        var newLink = fixReceiverType(link, WITHSCOPEFILTER);
        var expressionGuard = expressionGuard(name, owner);
        var filteredGuard = filterGuardReceiver(newLink, WITHSCOPEFILTER);
        return link.replaceMethods(
            filterReceiver(newLink.getInvocation(),WITHSCOPEFILTER),
            NashornGuards.combineGuards(expressionGuard,filteredGuard)
        );
    }

    private static MethodHandle filterGuardReceiver(GuardedInvocation link, MethodHandle receiverFilter) {
        var test = link.getGuard();
        if (test == null) {
            return null;
        }

        var receiverType = test.type().parameterType(0);
        var filter = MH.asType(receiverFilter, receiverFilter.type().changeParameterType(0, receiverType).changeReturnType(receiverType)
        );
        return filterReceiver(test, filter);
    }

    private static MethodHandle filterReceiver(MethodHandle mh, MethodHandle receiverFilter) {
        // With expression filter == receiverFilter, i.e. receiver is cast to withobject and its expression returned
        return MH.filterArguments(mh, 0, receiverFilter.asType(receiverFilter.type().changeReturnType(mh.type().parameterType(0))));
    }

    /**
     * Drops the WithObject wrapper from the expression.
     * @param receiver WithObject wrapper.
     * @return The with expression.
     */
    public static Object withFilterExpression(Object receiver) {
        return ((WithObject)receiver).expression;
    }

    @SuppressWarnings("unused")
    private static Object bindToExpression(Object fn, Object receiver) {
        if (fn instanceof ScriptFunction) {
            return bindToExpression((ScriptFunction) fn, receiver);
        } else if (fn instanceof ScriptObjectMirror) {
            var mirror = (ScriptObjectMirror)fn;
            if (mirror.isFunction()) {
                // We need to make sure correct 'this' is used for calls with Ident call expressions.
                // We do so here using an AbstractJSObject instance.
                return new AbstractJSObject() {
                    @Override
                    public Object call(Object thiz, Object... args) {
                        return mirror.call(withFilterExpression(receiver), args);
                    }
                };
            }
        }

        return fn;
    }

    private static Object bindToExpression(ScriptFunction fn, Object receiver) {
        return fn.createBound(withFilterExpression(receiver), ScriptRuntime.EMPTY_ARRAY);
    }

    private MethodHandle expressionGuard(String name, ScriptObject owner) {
        var map = expression.getMap();
        var sp = expression.getProtoSwitchPoints(name, owner);
        return MH.insertArguments(WITHEXPRESSIONGUARD, 1, map, sp);
    }

    @SuppressWarnings("unused")
    private static boolean withExpressionGuard(Object receiver, PropertyMap map, SwitchPoint[] sp) {
        return ((WithObject)receiver).expression.getMap() == map && !hasBeenInvalidated(sp);
    }

    private static boolean hasBeenInvalidated(SwitchPoint[] switchPoints) {
        if (switchPoints != null) {
            for (var switchPoint : switchPoints) {
                if (switchPoint.hasBeenInvalidated()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Drops the WithObject wrapper from the scope.
     * @param receiver WithObject wrapper.
     * @return The with scope.
     */
    public static Object withFilterScope(Object receiver) {
        return ((WithObject)receiver).getProto();
    }

    /**
     * Get the with expression for this {@code WithObject}
     */
    public ScriptObject getExpression() {
        return expression;
    }

    private static MethodHandle findOwnMH(String name, Class<?> rtype, Class<?>... types) {
        return MH.findStatic(MethodHandles.lookup(), WithObject.class, name, MH.type(rtype, types));
    }

}
