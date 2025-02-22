/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.strimzi.kproxy.util;

import java.nio.ByteBuffer;

import org.apache.kafka.common.utils.ByteBufferOutputStream;

import io.netty.buffer.ByteBuf;

/**
 * This class has been introduced as a work-around to allow using pooled {@link ByteBuf} instances
 * that are allowed to grow on demand while used on {@link org.apache.kafka.common.record.MemoryRecordsBuilder}
 * to create records (using {@link NettyMemoryRecords} factory methods).<br>
 */
public class ByteBufOutputStream extends ByteBufferOutputStream {

    private static final ByteBuffer DUMMY = ByteBuffer.allocate(0);
    private final int initialCapacity;
    private final int initialPosition;
    private final ByteBuf byteBuf;
    private ByteBuffer nioBuffer;

    public ByteBufOutputStream(final ByteBuf byteBuf) {
        super(DUMMY);
        if (byteBuf.nioBufferCount() != 1) {
            throw new IllegalArgumentException("Composite buffers are not supported");
        }
        this.byteBuf = byteBuf;
        this.nioBuffer = byteBuf.nioBuffer(byteBuf.writerIndex(), byteBuf.writableBytes());

        this.initialPosition = byteBuf.writerIndex();
        this.initialCapacity = nioBuffer.capacity();
    }

    @Override
    public void write(int b) {
        ensureRemaining(1);
        byteBuf.writeByte(b);
        nioBuffer.position(nioBuffer.position() + 1);
    }

    @Override
    public void write(byte[] bytes, int off, int len) {
        ensureRemaining(len);
        byteBuf.writeBytes(bytes, off, len);
        nioBuffer.position(nioBuffer.position() + len);
    }

    @Override
    public void write(ByteBuffer sourceBuffer) {
        final int writtenBytes = sourceBuffer.remaining();
        ensureRemaining(writtenBytes);
        byteBuf.writeBytes(sourceBuffer);
        nioBuffer.position(nioBuffer.position() + writtenBytes);
    }

    @Override
    public ByteBuffer buffer() {
        return nioBuffer;
    }

    public ByteBuf byteBuf() {
        return byteBuf;
    }

    @Override
    public int position() {
        return nioBuffer.position();
    }

    @Override
    public int remaining() {
        return nioBuffer.remaining();
    }

    @Override
    public int limit() {
        return nioBuffer.limit();
    }

    @Override
    public void position(int position) {
        final int delta = position - nioBuffer.position();
        ensureRemaining(delta);
        nioBuffer.position(position);
        byteBuf.writerIndex(byteBuf.writerIndex() + delta);
    }

    @Override
    public int initialCapacity() {
        return initialCapacity;
    }

    @Override
    public void ensureRemaining(int remainingBytesRequired) {
        if (remainingBytesRequired > byteBuf.writableBytes())
            expandBuffer(remainingBytesRequired);
    }

    private void expandBuffer(int remainingRequired) {
        byteBuf.ensureWritable(remainingRequired);
        final int position = nioBuffer.position();
        nioBuffer.position(0);
        nioBuffer = byteBuf.nioBuffer(initialPosition, byteBuf.capacity());
        nioBuffer.position(position);
    }

}
