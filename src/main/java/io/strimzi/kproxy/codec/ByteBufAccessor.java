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
package io.strimzi.kproxy.codec;

import java.nio.ByteBuffer;

import org.apache.kafka.common.protocol.Readable;
import org.apache.kafka.common.protocol.Writable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

/**
 * An implementation of Kafka's Readable and Writable abstraction in terms of
 * a Netty ByteBuf.
 * This allows us to re-use Kafka's generated {@code *RequestData} and
 * {@code *ResponseData} classes as-is.
 * This isn't completely ideal because the Kafka APIs for decoding of Records
 * depends on NIO ByteBuffer, so copying between ByteBuffer and ByteBuf cannot
 * always be avoided.
 */
public class ByteBufAccessor implements Readable, Writable {
    private final ByteBuf buf;

    public ByteBufAccessor(ByteBuf buf) {
        this.buf = buf;
    }

    private static IllegalArgumentException illegalVarintException(int value) {
        throw new IllegalArgumentException("Varint is too long, the most significant bit in the 5th byte is set, " +
                "converted value: " + Integer.toHexString(value));
    }

    private static IllegalArgumentException illegalVarlongException(long value) {
        throw new IllegalArgumentException("Varlong is too long, most significant bit in the 10th byte is set, " +
                "converted value: " + Long.toHexString(value));
    }

    /**
     * Read a long stored in variable-length format using zig-zag decoding from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html"> Google Protocol Buffers</a>.
     *
     * @param buffer The buffer to read from
     * @return The long value read
     *
     * @throws IllegalArgumentException if variable-length value does not terminate after 10 bytes have been read
     */
    public static long readVarlong(ByteBuf buffer) {
        long value = 0L;
        int i = 0;
        long b;
        while (((b = buffer.readByte()) & 0x80) != 0) {
            value |= (b & 0x7f) << i;
            i += 7;
            if (i > 63)
                throw illegalVarlongException(value);
        }
        value |= b << i;
        return (value >>> 1) ^ -(value & 1);
    }

    public static int readUnsignedVarint(ByteBuf buffer) {
        int value = 0;
        int i = 0;
        int b;
        while (((b = buffer.readByte()) & 0x80) != 0) {
            value |= (b & 0x7f) << i;
            i += 7;
            if (i > 28)
                throw illegalVarintException(value);
        }
        value |= b << i;
        return value;
    }

    public static int readVarint(ByteBuf buffer) {
        int value = readUnsignedVarint(buffer);
        return (value >>> 1) ^ -(value & 1);
    }

    public static void writeVarlong(long value, ByteBuf buffer) {
        long v = (value << 1) ^ (value >> 63);
        while ((v & 0xffffffffffffff80L) != 0L) {
            byte b = (byte) ((v & 0x7f) | 0x80);
            buffer.writeByte(b);
            v >>>= 7;
        }
        buffer.writeByte((byte) v);
    }

    public static void writeVarint(int value, ByteBuf buffer) {
        writeUnsignedVarint((value << 1) ^ (value >> 31), buffer);
    }

    public static void writeUnsignedVarint(int value, ByteBuf buffer) {
        while ((value & 0xffffff80) != 0L) {
            byte b = (byte) ((value & 0x7f) | 0x80);
            buffer.writeByte(b);
            value >>>= 7;
        }
        buffer.writeByte((byte) value);
    }

    @Override
    public byte readByte() {
        return buf.readByte();
    }

    @Override
    public short readShort() {
        return buf.readShort();
    }

    @Override
    public int readInt() {
        return buf.readInt();
    }

    @Override
    public long readLong() {
        return buf.readLong();
    }

    @Override
    public double readDouble() {
        return buf.readDouble();
    }

    @Override
    public void readArray(byte[] arr) {
        buf.readBytes(arr);
    }

    @Override
    public int readUnsignedVarint() {
        return readUnsignedVarint(buf);
    }

    @Override
    public ByteBuffer readByteBuffer(int length) {
        // TODO use buf.nioBufferCount() and buf.nioBuffers() to avoid the copy if possible
        ByteBuffer wrap = ByteBuffer.wrap(ByteBufUtil.getBytes(buf, buf.readerIndex(), length, false));
        buf.readerIndex(buf.readerIndex() + length);
        return wrap;

    }

    @Override
    public int readVarint() {
        return readVarint(buf);
    }

    @Override
    public long readVarlong() {
        return readVarlong(buf);
    }

    @Override
    public int remaining() {
        return buf.writerIndex() - buf.readerIndex();
    }

    @Override
    public void writeByte(byte val) {
        buf.writeByte(val);
    }

    @Override
    public void writeShort(short val) {
        buf.writeShort(val);
    }

    @Override
    public void writeInt(int val) {
        buf.writeInt(val);
    }

    @Override
    public void writeLong(long val) {
        buf.writeLong(val);
    }

    @Override
    public void writeDouble(double val) {
        buf.writeDouble(val);
    }

    @Override
    public void writeByteArray(byte[] arr) {
        buf.writeBytes(arr);
    }

    @Override
    public void writeUnsignedVarint(int i) {
        writeUnsignedVarint(i, buf);
    }

    @Override
    public void writeByteBuffer(ByteBuffer byteBuffer) {
        while (byteBuffer.hasRemaining()) {
            buf.writeByte(byteBuffer.get());
        }
    }

    @Override
    public void writeVarint(int i) {
        writeVarint(i, buf);
    }

    @Override
    public void writeVarlong(long i) {
        writeVarlong(i, buf);
    }
}
