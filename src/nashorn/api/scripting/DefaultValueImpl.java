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

package nashorn.api.scripting;

import nashorn.internal.runtime.JSType;

/**
 * Default implementation of {@link JSObject#getDefaultValue(Class)}.
 *
 * Isolated into a separate class mostly so that we can have private static instances of function name arrays, something we couldn't declare without it being visible in {@link JSObject} interface.
 */
class DefaultValueImpl {

    private static final String[] DEFAULT_VALUE_FNS_NUMBER = new String[] { "valueOf", "toString" };
    private static final String[] DEFAULT_VALUE_FNS_STRING = new String[] { "toString", "valueOf" };

    static Object getDefaultValue(JSObject jsobj, Class<?> hint) throws UnsupportedOperationException {
        var isNumber = hint == null || hint == Number.class;
        for (var methodName : isNumber ? DEFAULT_VALUE_FNS_NUMBER : DEFAULT_VALUE_FNS_STRING) {
            var objMember = jsobj.getMember(methodName);
            if (objMember instanceof JSObject) {
                var member = (JSObject)objMember;
                if (member.isFunction()) {
                    var value = member.call(jsobj);
                    if (JSType.isPrimitive(value)) {
                        return value;
                    }
                }
            }
        }
        throw new UnsupportedOperationException(isNumber ? "cannot.get.default.number" : "cannot.get.default.string");
    }

}
