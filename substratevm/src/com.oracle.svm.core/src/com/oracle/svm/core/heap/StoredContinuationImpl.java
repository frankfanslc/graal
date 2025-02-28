/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.heap;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.collections.Pair;
import org.graalvm.compiler.api.replacements.Fold;
import org.graalvm.compiler.core.common.util.TypeConversion;
import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.annotate.AlwaysInline;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.c.NonmovableArray;
import com.oracle.svm.core.code.CodeInfo;
import com.oracle.svm.core.code.CodeInfoAccess;
import com.oracle.svm.core.code.CodeInfoTable;
import com.oracle.svm.core.code.FrameInfoQueryResult;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.deopt.DeoptimizedFrame;
import com.oracle.svm.core.graal.nodes.NewStoredContinuationNode;
import com.oracle.svm.core.stack.JavaStackWalker;
import com.oracle.svm.core.stack.StackFrameVisitor;
import com.oracle.svm.core.thread.Continuation;
import com.oracle.svm.core.thread.Safepoint;
import com.oracle.svm.core.util.UnsignedUtils;
import com.oracle.svm.core.util.VMError;

import jdk.vm.ci.meta.JavaKind;

/**
 * Helper class to access a {@link StoredContinuation}.
 *
 * Memory layout of a {@link StoredContinuation} instance:
 *
 * Each {@link StoredContinuation} has a fixed-size header (16 bytes) which stores the size of the
 * instance, after which follows the data of the stored frame(s).
 *
 * <pre>
 *      +-0x0-|-0x4-|-0x8-|-0xa-+
 * 0x00 | hub | (1) | (2) | (3) | instance header: 1) identity hash code; 2) size in words; 3) number of stored frames
 *      +-----------------------+
 * 0x10 | -  (1)  - | (2) | (3) | 8-byte shared header for all frames: 1) nonmovable address of reference map, which should be same for all frames.
 *      : more per-frame headers: 8-byte per-frame header: 2) size of frame in bytes; 3) reference map index of frame
 *      : continuous frame data :
 * </pre>
 */
public final class StoredContinuationImpl {
    private static final int FRAME_COUNT_OFFSET_TO_PAYLOAD = -4;
    private static final int SIZE_OFFSET_TO_PAYLOAD = -8;

    private static final int VALID_OFFSET_START = SIZE_OFFSET_TO_PAYLOAD;

    public static final int PAYLOAD_OFFSET = 16;

    private static final int SHARED_REFERENCE_MAP_ENCODING_OFFSET = 0;
    private static final int SHARED_REFERENCE_MAP_ENCODING_SIZE = 8;
    private static final int FRAME_META_START_OFFSET = SHARED_REFERENCE_MAP_ENCODING_OFFSET + SHARED_REFERENCE_MAP_ENCODING_SIZE;
    private static final int FRAME_META_SIZE = 8;
    private static final int SIZE_OFFSET_IN_FRAME_META = 0;
    private static final int REFERENCE_MAP_INDEX_OFFSET_IN_FRAME_META = 4;

    // use for hard-coded size calculation
    private static final int HEADER_SIZE = PAYLOAD_OFFSET;

    @Uninterruptible(reason = "in allocation of StoredContinuation instance")
    public static void initializeNewlyAllocated(Object obj) {
        Pointer p = Word.objectToUntrackedPointer(obj).add(PAYLOAD_OFFSET);
        // Keep GC from taking a closer look until frames are initialized
        p.writeInt(FRAME_COUNT_OFFSET_TO_PAYLOAD, 0, LocationIdentity.init());
    }

    /* All method calls in this function except `allocate` should be `@Uninterruptible` */
    private static StoredContinuation allocate(int payloadSize) {
        assert payloadSize % 8 == 0;
        StoredContinuation f = NewStoredContinuationNode.allocate(payloadSize);
        assert readPayloadSize(f) == payloadSize;
        return f;
    }

    // validator functions

    @Uninterruptible(reason = "read StoredContinuation")
    private static boolean checkOffset(StoredContinuation f, int offset) {
        boolean result = true;
        assert result &= offset % 4 == 0;
        if (offset >= 0) {
            assert result &= offset < readSize(f);
        } else {
            assert result &= offset >= VALID_OFFSET_START;
        }
        return result;
    }

    @Uninterruptible(reason = "read StoredContinuation")
    private static boolean checkPayloadOffset(StoredContinuation f, int offset) {
        boolean valid = true;
        assert valid &= offset % 4 == 0;
        assert valid &= offset >= 0;
        assert valid &= offset < readSize(f);
        return valid;
    }

    // read/write functions

    @Uninterruptible(reason = "read StoredContinuation")
    private static int readPayloadInt(StoredContinuation f, int offset) {
        checkOffset(f, offset);
        return payloadLocation(f).readInt(offset);
    }

    @Uninterruptible(reason = "read StoredContinuation")
    private static long readPayloadLong(StoredContinuation f, int offset) {
        checkOffset(f, offset);
        return payloadLocation(f).readLong(offset);
    }

    @Uninterruptible(reason = "write StoredContinuation")
    private static void writePayloadLong(StoredContinuation f, int offset, long value) {
        checkPayloadOffset(f, offset);
        payloadLocation(f).writeLong(offset, value);
    }

    @Uninterruptible(reason = "write StoredContinuation")
    private static void writePayloadInt(StoredContinuation f, int offset, int value) {
        checkPayloadOffset(f, offset);
        payloadLocation(f).writeInt(offset, value);
    }

    // size of payload
    @Uninterruptible(reason = "read StoredContinuation")
    private static int readPayloadSize(StoredContinuation f) {
        return readPayloadInt(f, SIZE_OFFSET_TO_PAYLOAD);
    }

    // size of raw frame
    @Uninterruptible(reason = "read StoredContinuation")
    public static int readAllFrameSize(StoredContinuation f) {
        return readPayloadSize(f) - readFrameMetaSize(f);
    }

    // size of object
    @Uninterruptible(reason = "read StoredContinuation")
    public static int readSize(StoredContinuation f) {
        return readPayloadSize(f) + HEADER_SIZE;
    }

    @Uninterruptible(reason = "read StoredContinuation")
    public static int readFrameCount(StoredContinuation f) {
        return readPayloadInt(f, FRAME_COUNT_OFFSET_TO_PAYLOAD);
    }

    // read frame meta-data

    @Uninterruptible(reason = "read StoredContinuation")
    private static NonmovableArray<Byte> readReferenceMapEncoding(StoredContinuation f) {
        return WordFactory.pointer(payloadLocation(f).readLong(SHARED_REFERENCE_MAP_ENCODING_OFFSET));
    }

    @Uninterruptible(reason = "read StoredContinuation")
    private static int readFrameMetaSize(StoredContinuation f) {
        return SHARED_REFERENCE_MAP_ENCODING_SIZE + readFrameCount(f) * FRAME_META_SIZE;
    }

    @Uninterruptible(reason = "read StoredContinuation")
    public static int readFrameSize(StoredContinuation f, int frameIndex) {
        return payloadLocation(f).readInt(SHARED_REFERENCE_MAP_ENCODING_SIZE + frameIndex * FRAME_META_SIZE + SIZE_OFFSET_IN_FRAME_META);
    }

    @Uninterruptible(reason = "read StoredContinuation")
    private static int readReferenceMapIndex(StoredContinuation f, int frameIndex) {
        return payloadLocation(f).readInt(SHARED_REFERENCE_MAP_ENCODING_SIZE + frameIndex * FRAME_META_SIZE + REFERENCE_MAP_INDEX_OFFSET_IN_FRAME_META);
    }

    // Pointers

    @Uninterruptible(reason = "read/write StoredContinuation")
    private static Pointer payloadLocation(StoredContinuation f) {
        return Word.objectToUntrackedPointer(f).add(PAYLOAD_OFFSET);
    }

    @Uninterruptible(reason = "read/write StoredContinuation")
    public static Pointer payloadFrameStart(StoredContinuation f) {
        return payloadLocation(f).add(readFrameMetaSize(f));
    }

    /** A non-uninterruptible function to allocate temporary buffer. */
    public static byte[] allocateBuf(StoredContinuation f) {
        return new byte[readAllFrameSize(f)];
    }

    @Uninterruptible(reason = "access stack")
    public static void writeBuf(StoredContinuation f, byte[] buf) {
        Pointer frameStart = payloadFrameStart(f);
        UnmanagedMemoryUtil.copy(frameStart, Word.objectToUntrackedPointer(buf).add(getByteArrayBaseOffset()), WordFactory.unsigned(buf.length));
    }

    public static int allocateFromCurrentStack(Continuation cont, Pointer rootSp, Pointer leafSp, CodePointer leafIp) {
        return allocateFromStack(cont, rootSp, leafSp, leafIp, WordFactory.nullPointer());
    }

    public static int allocateFromForeignStack(Continuation cont, Pointer rootSp, IsolateThread thread) {
        return allocateFromStack(cont, rootSp, WordFactory.nullPointer(), WordFactory.nullPointer(), thread);
    }

    /**
     * Return value follows the semantic of preempt status and pinned reason, 0 means yielding
     * successfully.
     */
    private static int allocateFromStack(Continuation cont, Pointer rootSp, Pointer leafSP, CodePointer leafIp, IsolateThread otherThread) {
        boolean isCurrentThread = leafSP.isNonNull();

        YieldVisitor visitor = new YieldVisitor(rootSp, leafSP, leafIp);
        Pointer resultLeafSP = leafSP;

        if (isCurrentThread) {
            VMError.guarantee(otherThread.isNull());
            JavaStackWalker.walkCurrentThread(leafSP, visitor);
        } else {
            JavaStackWalker.walkThread(otherThread, visitor);
            resultLeafSP = visitor.leafSP;
        }

        if (visitor.preemptStatus != Continuation.YIELD_SUCCESS) {
            return visitor.preemptStatus;
        }

        if (!isCurrentThread) {
            cont.setIP(visitor.leafIP);
        }
        VMError.guarantee(resultLeafSP.isNonNull());

        int frameCount = visitor.frameSizeReferenceMapIndex.size();
        int payloadSize = SHARED_REFERENCE_MAP_ENCODING_SIZE + FRAME_META_SIZE * frameCount + UnsignedUtils.safeToInt(rootSp.subtract(resultLeafSP));

        cont.stored = allocate(payloadSize);
        /*
         * At this point, the object contains all zeros, which includes the frame count, so if we
         * are interrupted by a GC, it will not attempt to make sense of it. Only at the end of this
         * method we uninterruptibly initialize the frame count and copy the stack frames.
         */

        writePayloadLong(cont.stored, SHARED_REFERENCE_MAP_ENCODING_OFFSET, visitor.referenceMapEncoding.rawValue());

        int allFrameSize = 0;
        for (int i = 0; i < frameCount; i++) {
            Pair<Integer, Integer> frameSizeRefMapInxPair = visitor.frameSizeReferenceMapIndex.get(i);
            writePayloadInt(cont.stored, FRAME_META_START_OFFSET + i * FRAME_META_SIZE + SIZE_OFFSET_IN_FRAME_META,
                            frameSizeRefMapInxPair.getLeft());
            writePayloadInt(cont.stored, FRAME_META_START_OFFSET + i * FRAME_META_SIZE + REFERENCE_MAP_INDEX_OFFSET_IN_FRAME_META,
                            frameSizeRefMapInxPair.getRight());
            allFrameSize += frameSizeRefMapInxPair.getLeft();
        }

        copyUninterruptibly(cont, resultLeafSP, allFrameSize, frameCount);
        return Continuation.YIELD_SUCCESS;
    }

    @Uninterruptible(reason = "Prevent modifications to the stack while copying.")
    private static void copyUninterruptibly(Continuation cont, Pointer sp, int size, int frameCount) {
        payloadLocation(cont.stored).writeInt(FRAME_COUNT_OFFSET_TO_PAYLOAD, frameCount);

        VMError.guarantee(size == readAllFrameSize(cont.stored));
        Pointer frameStart = payloadFrameStart(cont.stored);
        UnmanagedMemoryUtil.copy(sp, frameStart, WordFactory.unsigned(size));
    }

    /**
     * Copied from {@link InstanceReferenceMapDecoder#walkOffsetsFromPointer}, walk references
     * stored in {@link StoredContinuation} object.
     */
    @AlwaysInline("de-virtualize calls to ObjectReferenceVisitor")
    public static boolean walkStoredContinuationFromPointer(Pointer baseAddress, ObjectReferenceVisitor visitor, Object holderObject) {
        StoredContinuation f = (StoredContinuation) holderObject;

        Pointer payloadStart = StoredContinuationImpl.payloadLocation(f);
        assert payloadStart.subtract(baseAddress).equal(StoredContinuationImpl.HEADER_SIZE) : "base address not pointing to frame instance";

        int frameCount = StoredContinuationImpl.readFrameCount(f);
        int size = StoredContinuationImpl.readPayloadSize(f);

        Pointer curFrame = StoredContinuationImpl.payloadFrameStart(f);
        int frameIndex = 0;

        NonmovableArray<Byte> referenceMapEncoding = StoredContinuationImpl.readReferenceMapEncoding(f);
        for (; frameIndex < frameCount; frameIndex++) {
            int frameSize = StoredContinuationImpl.readFrameSize(f, frameIndex);
            int referenceMapIndex = StoredContinuationImpl.readReferenceMapIndex(f, frameIndex);

            boolean r = CodeReferenceMapDecoder.walkOffsetsFromPointer(curFrame, referenceMapEncoding, referenceMapIndex, visitor);

            if (!r) {
                return false;
            }
            curFrame = curFrame.add(frameSize);
        }

        assert frameIndex == frameCount;
        // NOTE: frameCount can be 0 if the object has just been allocated but not filled yet
        assert frameCount == 0 || curFrame.subtract(payloadStart).equal(size);

        return true;
    }

    private static class YieldVisitor extends StackFrameVisitor {
        int preemptStatus = Continuation.YIELD_SUCCESS;

        Pointer rootSP;
        Pointer leafSP;
        CodePointer leafIP;

        List<Pair<Integer, Integer>> frameSizeReferenceMapIndex = new ArrayList<>();
        NonmovableArray<Byte> referenceMapEncoding = WordFactory.nullPointer();

        private static boolean startFromNextFrame = false;

        YieldVisitor(Pointer rootSp, Pointer verifyLeafSp, CodePointer leafIp) {
            if (verifyLeafSp.isNonNull()) {
                VMError.guarantee(verifyLeafSp.belowThan(rootSp));
            }
            this.rootSP = rootSp;
            this.leafSP = verifyLeafSp;
            this.leafIP = leafIp;
        }

        @SuppressWarnings("hiding")
        @Override
        protected boolean visitFrame(Pointer sp, CodePointer ip, CodeInfo codeInfo, DeoptimizedFrame deoptimizedFrame) {
            FrameInfoQueryResult frameInfo = CodeInfoTable.lookupCodeInfoQueryResult(codeInfo, ip).getFrameInfo();
            if (frameInfo.getSourceClass().equals(StoredContinuationImpl.class) && frameInfo.getSourceMethodName().equals("allocateFromStack")) {
                preemptStatus = Continuation.YIELDING;
                return false;
            }

            if (leafSP.isNull()) {
                // We're preempting a continuation, walk starts from a safepoint.
                // Should start from the method calling `enterSlowPathSafepointCheck`.
                if (startFromNextFrame) {
                    leafSP = sp;
                    leafIP = ip;
                } else {
                    if (frameInfo.getSourceClass().equals(Safepoint.class) && frameInfo.getSourceMethodName().equals("enterSlowPathSafepointCheck")) {
                        startFromNextFrame = true;
                    }
                    return true;
                }
            } else if (frameSizeReferenceMapIndex.isEmpty()) {
                // yielding current thread,
                // `leafSP` and `leafIP` are used for verification purpose.
                VMError.guarantee(leafSP.equal(sp));
                VMError.guarantee(leafIP.equal(ip));
            }

            NonmovableArray<Byte> referenceMapEncoding = CodeInfoAccess.getStackReferenceMapEncoding(codeInfo);
            if (this.referenceMapEncoding.isNull()) {
                this.referenceMapEncoding = referenceMapEncoding;
            } else {
                VMError.guarantee(this.referenceMapEncoding.equal(referenceMapEncoding));
            }

            long relIp = CodeInfoAccess.relativeIP(codeInfo, ip);
            int frameSize = TypeConversion.asU4(CodeInfoAccess.lookupTotalFrameSize(codeInfo, relIp));
            int referenceMapIndex = TypeConversion.asS4(CodeInfoAccess.lookupStackReferenceMapIndex(codeInfo, relIp));
            frameSizeReferenceMapIndex.add(Pair.create(frameSize, referenceMapIndex));

            Pointer currentFrameEnd = sp.add(frameSize);
            VMError.guarantee(currentFrameEnd.belowOrEqual(rootSP));

            return currentFrameEnd.notEqual(rootSP);
        }
    }

    @Fold
    protected static int getByteArrayBaseOffset() {
        return ConfigurationValues.getObjectLayout().getArrayBaseOffset(JavaKind.Byte);
    }
}
