/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.persistence.wal;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.internal.pagemem.wal.WALIterator;
import org.apache.ignite.internal.pagemem.wal.WALPointer;
import org.apache.ignite.internal.pagemem.wal.record.WALRecord;
import org.apache.ignite.internal.processors.cache.GridCacheSharedContext;
import org.apache.ignite.internal.processors.cache.persistence.wal.record.HeaderRecord;
import org.apache.ignite.internal.util.GridCloseableIteratorAdapter;
import org.apache.ignite.lang.IgniteBiTuple;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Iterator over WAL segments. This abstract class provides most functionality for reading records in log.
 * Subclasses are to override segment switching functionality
 */
public abstract class AbstractWalRecordsIterator extends GridCloseableIteratorAdapter<IgniteBiTuple<WALPointer, WALRecord>>
    implements WALIterator {
    /** */
    private static final long serialVersionUID = 0L;

    /**
     * Current record preloaded, to be returned on next()<br>
     * Normally this should be not null because advance() method should already prepare some value
     */
    protected IgniteBiTuple<WALPointer, WALRecord> curRec;

    /**
     * Current WAL segment absolute index. <br>
     * Determined as lowest number of file at start, is changed during advance segment
     */
    protected long curIdx = -1;

    /**
     * Current WAL segment read file handle. To be filled by subclass advanceSegment
     */
    private FileWriteAheadLogManager.ReadFileHandle currWalSegment;

    /** Logger */
    @NotNull
    protected final IgniteLogger log;

    /** Shared context for creating serializer of required version and grid name access */
    @NotNull
    private final GridCacheSharedContext sharedCtx;

    /** Serializer of current version to read headers. */
    @NotNull
    private final RecordSerializer serializer;

    /** Utility buffer for reading records */
    private final ByteBuffer buf;

    /**
     * @param log Logger
     * @param sharedCtx Shared context
     * @param serializer Serializer of current version to read headers.
     * @param bufSize buffer for reading records size
     */
    protected AbstractWalRecordsIterator(
        @NotNull final IgniteLogger log,
        @NotNull final GridCacheSharedContext sharedCtx,
        @NotNull final RecordSerializer serializer,
        final int bufSize) {
        this.log = log;
        this.sharedCtx = sharedCtx;
        this.serializer = serializer;

        // Do not allocate direct buffer for iterator.
        buf = ByteBuffer.allocate(bufSize);
        buf.order(ByteOrder.nativeOrder());

    }

    /**
     * Scans provided folder for a WAL segment files
     * @param walFilesDir directory to scan
     * @return found WAL file descriptors
     */
    protected static FileWriteAheadLogManager.FileDescriptor[] loadFileDescriptors(@NotNull final File walFilesDir) throws IgniteCheckedException {
        final File[] files = walFilesDir.listFiles(FileWriteAheadLogManager.WAL_SEGMENT_FILE_FILTER);
        if (files == null) {
            throw new IgniteCheckedException("WAL files directory does not not denote a " +
                "directory, or if an I/O error occurs: [" + walFilesDir.getAbsolutePath() + "]");
        }
        return FileWriteAheadLogManager.scan(files);
    }

    /** {@inheritDoc} */
    @Override protected IgniteBiTuple<WALPointer, WALRecord> onNext() throws IgniteCheckedException {
        IgniteBiTuple<WALPointer, WALRecord> ret = curRec;

        advance();

        return ret;
    }

    /** {@inheritDoc} */
    @Override protected boolean onHasNext() throws IgniteCheckedException {
        return curRec != null;
    }

    /**
     * Switches records iterator to the next record. If end of segment reached, switch to new segment is called
     * @throws IgniteCheckedException If failed.
     */
    protected void advance() throws IgniteCheckedException {
        while (true) {
            advanceRecord(currWalSegment);

            if (curRec != null)
                return;
            else {
                currWalSegment = advanceSegment(currWalSegment);

                if (currWalSegment == null)
                    return;
            }
        }
    }

    /**
     * Closes and returns WAL segment (if any)
     * @return closed handle
     * @throws IgniteCheckedException if IO failed
     */
    @Nullable protected FileWriteAheadLogManager.ReadFileHandle closeCurrentWalSegment() throws IgniteCheckedException {
        final FileWriteAheadLogManager.ReadFileHandle walSegmentClosed = currWalSegment;
        if (walSegmentClosed != null) {
            walSegmentClosed.close();
            currWalSegment = null;
        }
        return walSegmentClosed;
    }

    /**
     * Switches records iterator to the next WAL segment
     * as result of this method, new reference to segment should be returned.
     * Null for current handle means stop of iteration
     * @throws IgniteCheckedException if reading failed
     * @param curWalSegment current open WAL segment or null if there is no open segment yet
     * @return new WAL segment to read or null for stop iteration
     */
    protected abstract FileWriteAheadLogManager.ReadFileHandle advanceSegment(
        @Nullable final FileWriteAheadLogManager.ReadFileHandle curWalSegment) throws IgniteCheckedException;

    /**
     * Switches {@link #curRec} to new record
     * @param curHandle currently opened read handle
     */
    private void advanceRecord(
        FileWriteAheadLogManager.ReadFileHandle curHandle) {
        FileWALPointer ptr = null;
        try {
            FileWriteAheadLogManager.ReadFileHandle hnd = curHandle;

            if (hnd != null) {
                RecordSerializer ser = hnd.ser;

                int pos = (int)hnd.in.position();

                ptr = new FileWALPointer(hnd.idx, pos, 0);

                WALRecord rec = ser.readRecord(hnd.in, ptr);

                ptr.length(rec.size());

                //using diamond operator here can break compile for 7
                curRec = new IgniteBiTuple<WALPointer, WALRecord>(ptr, rec);
            }
        }
        catch (IOException | IgniteCheckedException e) {
            if (!(e instanceof SegmentEofException))
                handleRecordException(e, ptr);
            curRec = null;
        }
    }

    /**
     * Handler for record deserialization exception
     * @param e problem from records reading
     * @param ptr file pointer was accessed
     */
    protected void handleRecordException(
        @NotNull final Exception e,
        @Nullable final FileWALPointer ptr) {
        if (log.isInfoEnabled())
            log.info("Stopping WAL iteration due to an exception: " + e.getMessage());
    }

    /**
     * @param desc File descriptor.
     * @param start Optional start pointer. Null means read from the beginning
     * @return Initialized file handle.
     * @throws FileNotFoundException If segment file is missing.
     * @throws IgniteCheckedException If initialized failed due to another unexpected error.
     */
    protected FileWriteAheadLogManager.ReadFileHandle initReadHandle(
        @NotNull final FileWriteAheadLogManager.FileDescriptor desc,
        @Nullable final FileWALPointer start)
        throws IgniteCheckedException, FileNotFoundException {
        try {
            RandomAccessFile rf = new RandomAccessFile(desc.file, "r");

            try {
                FileChannel ch = rf.getChannel();
                FileInput in = new FileInput(ch, buf);

                // Header record must be agnostic to the serializer version.
                WALRecord rec = serializer.readRecord(in,
                    new FileWALPointer(desc.idx, (int)ch.position(), 0));

                if (rec == null)
                    return null;

                if (rec.type() != WALRecord.RecordType.HEADER_RECORD)
                    throw new IOException("Missing file header record: " + desc.file.getAbsoluteFile());

                int ver = ((HeaderRecord)rec).version();

                RecordSerializer ser = FileWriteAheadLogManager.forVersion(sharedCtx, ver);

                if (start != null && desc.idx == start.index())
                    in.seek(start.fileOffset());

                return new FileWriteAheadLogManager.ReadFileHandle(rf, desc.idx, sharedCtx.igniteInstanceName(), ser, in);
            }
            catch (SegmentEofException | EOFException ignore) {
                try {
                    rf.close();
                }
                catch (IOException ce) {
                    throw new IgniteCheckedException(ce);
                }

                return null;
            }
            catch (IOException | IgniteCheckedException e) {
                try {
                    rf.close();
                }
                catch (IOException ce) {
                    e.addSuppressed(ce);
                }

                throw e;
            }
        }
        catch (FileNotFoundException e) {
            throw e;
        }
        catch (IOException e) {
            throw new IgniteCheckedException(
                "Failed to initialize WAL segment: " + desc.file.getAbsolutePath(), e);
        }
    }

}
