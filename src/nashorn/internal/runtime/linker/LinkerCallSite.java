/*
 * Copyright (c) 2010, 2017, Oracle and/or its affiliates. All rights reserved.
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

package nashorn.internal.runtime.linker;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import jdk.dynalink.support.ChainedCallSite;

/**
 * Relinkable form of call site.
 */
public class LinkerCallSite extends ChainedCallSite {

    /** Maximum number of arguments passed directly. */
    public static final int ARGLIMIT = 125;

    LinkerCallSite(NashornCallSiteDescriptor descriptor) {
        super(descriptor);
    }

    /**
     * Construct a new linker call site.
     * @param name     Name of method.
     * @param type     Method type.
     * @param flags    Call site specific flags.
     * @return New LinkerCallSite.
     */
    static LinkerCallSite newLinkerCallSite(MethodHandles.Lookup lookup, String name, MethodType type, int flags) {
        final NashornCallSiteDescriptor desc = NashornCallSiteDescriptor.get(lookup, name, type, flags);
        return new LinkerCallSite(desc);
    }

    @Override
    public String toString() {
        return getDescriptor().toString();
    }

    /**
     * Get the descriptor for this callsite
     * @return a {@link NashornCallSiteDescriptor}
     */
    public NashornCallSiteDescriptor getNashornDescriptor() {
        return (NashornCallSiteDescriptor)getDescriptor();
    }

    @Override
    protected int getMaxChainLength() {
        return 8;
    }

}
