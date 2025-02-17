/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.genscavenge;

import org.graalvm.compiler.api.replacements.Fold;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.heap.GCCause;

class JfrGCEvents {
    public static long getTicks() {
        if (!hasJfrSupport()) {
            return 0;
        }
        return jfrSupport().getTicks();
    }

    public static long startGCPhasePause() {
        if (!hasJfrSupport()) {
            return 0;
        }
        return jfrSupport().startGCPhasePause();
    }

    public static void emitGarbageCollectionEvent(UnsignedWord gcEpoch, GCCause cause, long start) {
        if (!hasJfrSupport()) {
            return;
        }
        jfrSupport().emitGarbageCollectionEvent(gcEpoch, cause, start);
    }

    public static void emitGCPhasePauseEvent(UnsignedWord gcEpoch, String name, long startTicks) {
        if (!hasJfrSupport()) {
            return;
        }
        jfrSupport().emitGCPhasePauseEvent(gcEpoch, name, startTicks);
    }

    @Fold
    static boolean hasJfrSupport() {
        return ImageSingletons.contains(JfrGCEventSupport.class);
    }

    @Fold
    static JfrGCEventSupport jfrSupport() {
        return ImageSingletons.lookup(JfrGCEventSupport.class);
    }
}
