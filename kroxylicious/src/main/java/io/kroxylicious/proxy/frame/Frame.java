/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.frame;

import io.netty.buffer.ByteBuf;

/**
 * A frame in the Kafka protocol, which may or may not be fully decoded.
 */
public interface Frame {

    /**
     * Estimate the expected encoded size in bytes of this {@code Frame}.<br>
     * In particular, written data by {@link #encode(ByteBuf)} should be the same as reported by this method.
     */
    int estimateEncodedSize();

    /**
     * Write the frame, including the size prefix, to the given buffer
     * @param out The output buffer
     */
    void encode(ByteBuf out);

    /**
     * The correlation id.
     * @return The correlation id.
     */
    int correlationId();

}