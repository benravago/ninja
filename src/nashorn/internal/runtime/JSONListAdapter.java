/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.util.Collection;
import java.util.List;
import java.util.Set;
import nashorn.api.scripting.JSObject;
import nashorn.api.scripting.ScriptObjectMirror;
import nashorn.internal.objects.Global;

/**
 * A {@link ListAdapter} that also implements {@link JSObject}.
 * Named {@code JSONListAdapter} as it is used as a {@code JSObject} implementing the {@link List} interface, which is the expected interface to be implemented by JSON-parsed arrays when they are handled in Java.
 * We aren't implementing {@link JSObject} on {@link ListAdapter} directly since that'd have implications for other uses of list adapter (e.g. interferences of JSObject default value calculation vs. List's {@code toString()} etc.)
 */
public final class JSONListAdapter extends ListAdapter implements JSObject {

    /**
     * Creates a new JSON list adapter.
     * @param obj the underlying object being exposed as a list.
     * @param global the home global of the underlying object.
     */
    public JSONListAdapter(JSObject obj, Global global) {
        super(obj, global);
    }

    /**
     * Unwraps this adapter into its underlying non-JSObject representative.
     * @param homeGlobal the home global for unwrapping
     * @return either the unwrapped object or this if it should not be unwrapped in the specified global.
     */
    public Object unwrap(Object homeGlobal) {
        var unwrapped = ScriptObjectMirror.unwrap(obj, homeGlobal);
        return unwrapped != obj ? unwrapped : this;
    }

    @Override
    public Object call(Object thiz, Object... args) {
        return obj.call(thiz, args);
    }

    @Override
    public Object newObject(Object... args) {
        return obj.newObject(args);
    }

    @Override
    public Object eval(String s) {
        return obj.eval(s);
    }

    @Override
    public Object getMember(String name) {
        return obj.getMember(name);
    }

    @Override
    public Object getSlot(int index) {
        return obj.getSlot(index);
    }

    @Override
    public boolean hasMember(String name) {
        return obj.hasMember(name);
    }

    @Override
    public boolean hasSlot(int slot) {
        return obj.hasSlot(slot);
    }

    @Override
    public void removeMember(String name) {
        obj.removeMember(name);
    }

    @Override
    public void setMember(String name, Object value) {
        obj.setMember(name, value);
    }

    @Override
    public void setSlot(int index, Object value) {
        obj.setSlot(index, value);
    }

    @Override
    public Set<String> keySet() {
        return obj.keySet();
    }

    @Override
    public Collection<Object> values() {
        return obj.values();
    }

    @Override
    public boolean isInstance(Object instance) {
        return obj.isInstance(instance);
    }

    @Override
    public boolean isInstanceOf(Object clazz) {
        return obj.isInstanceOf(clazz);
    }

    @Override
    public String getClassName() {
        return obj.getClassName();
    }

    @Override
    public boolean isFunction() {
        return obj.isFunction();
    }

    @Override
    public boolean isArray() {
        return obj.isArray();
    }

    @Override
    public Object getDefaultValue(Class<?> hint) throws UnsupportedOperationException {
        return obj.getDefaultValue(hint);
    }

}
