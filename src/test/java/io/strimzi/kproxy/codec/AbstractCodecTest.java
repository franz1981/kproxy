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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.kafka.common.message.ApiMessageType;
import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.protocol.ByteBufferAccessor;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.protocol.ObjectSerializationCache;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Baseclass for codec tests
 */
public abstract class AbstractCodecTest {

    public static final DecodePredicate ALWAYS_DECODE = new DecodePredicate() {

        @Override
        public boolean shouldDecodeRequest(ApiKeys apiKey, int apiVersion) {
            return true;
        }

        @Override
        public boolean shouldDecodeResponse(ApiKeys apiKey, int apiVersion) {
            return true;
        }
    };
    public static final DecodePredicate NEVER_DECODE = new DecodePredicate() {
        @Override
        public boolean shouldDecodeRequest(ApiKeys apiKey, int apiVersion) {
            return false;
        }

        @Override
        public boolean shouldDecodeResponse(ApiKeys apiKey, int apiVersion) {
            return false;
        }
    };

    public static List<? extends Class<?>> messageClasses(List<Object> messages) {
        return messages.stream().map(Object::getClass).collect(Collectors.toList());
    }

    public static Stream<Short> requestApiVersions() {
        return requestApiVersions(ApiMessageType.API_VERSIONS);
    }

    public static Stream<Short> requestApiVersions(ApiMessageType type) {
        return IntStream.rangeClosed(type.lowestSupportedVersion(), type.highestSupportedVersion())
                .mapToObj(version -> (short) version);
    }

    public static ApiVersionsRequestData exampleApiVersionsRequest() {
        return new ApiVersionsRequestData()
                .setClientSoftwareName("foo/bar")
                .setClientSoftwareVersion("1.2.0");
    }

    protected static ApiVersionsResponseData exampleApiVersionsResponse() {
        ApiVersionsResponseData.ApiVersionCollection ak = new ApiVersionsResponseData.ApiVersionCollection();
        for (var apiKey : ApiKeys.values()) {
            ak.add(new ApiVersionsResponseData.ApiVersion()
                    .setApiKey(apiKey.id)
                    .setMinVersion(apiKey.messageType.lowestSupportedVersion())
                    .setMaxVersion(apiKey.messageType.highestSupportedVersion()));
        }

        return new ApiVersionsResponseData()
                .setErrorCode(Errors.NONE.code())
                .setThrottleTimeMs(12)
                .setFinalizedFeaturesEpoch(1)
                .setApiKeys(ak)
                .setSupportedFeatures(new ApiVersionsResponseData.SupportedFeatureKeyCollection())
                .setFinalizedFeatures(new ApiVersionsResponseData.FinalizedFeatureKeyCollection());
    }

    protected static RequestHeaderData exampleRequestHeader(short apiVersion) {
        return new RequestHeaderData()
                .setClientId("fooooo")
                .setCorrelationId(12)
                .setRequestApiKey(ApiKeys.API_VERSIONS.id)
                .setRequestApiVersion(apiVersion);
    }

    public static ResponseHeaderData exampleResponseHeader() {
        return new ResponseHeaderData()
                .setCorrelationId(12);
    }

    protected static RequestHeaderData deserializeRequestHeaderUsingKafkaApis(short headerVersion, ByteBuffer buffer) {
        return new RequestHeaderData(new ByteBufferAccessor(buffer), headerVersion);
    }

    protected static ResponseHeaderData deserializeResponseHeaderUsingKafkaApis(short headerVersion, ByteBuffer buffer) {
        return new ResponseHeaderData(new ByteBufferAccessor(buffer), headerVersion);
    }

    protected static ApiVersionsRequestData deserializeApiVersionsRequestUsingKafkaApis(short apiVersion, ByteBuffer buffer) {
        return new ApiVersionsRequestData(new ByteBufferAccessor(buffer), apiVersion);
    }

    protected static ApiVersionsResponseData deserializeApiVersionsResponseUsingKafkaApis(short apiVersion, ByteBuffer buffer) {
        return new ApiVersionsResponseData(new ByteBufferAccessor(buffer), apiVersion);
    }

    protected static ByteBuffer serializeUsingKafkaApis(short headerVersion, ApiMessage header, short apiVersion, ApiMessage body) {
        return serializeUsingKafkaApis(1, headerVersion, header, apiVersion, body);
    }

    protected static ByteBuffer serializeUsingKafkaApis(int numCopies, short headerVersion, ApiMessage header, short apiVersion, ApiMessage body) {
        var cache = new ObjectSerializationCache();
        int headerSize = header.size(cache, headerVersion);
        int bodySize = body.size(cache, apiVersion);
        ByteBuffer bbuffer = ByteBuffer.allocate(numCopies * (4 + headerSize + bodySize));
        for (int i = 0; i < numCopies; i++) {
            bbuffer.putInt(headerSize + bodySize);
            var kafkaAccessor = new ByteBufferAccessor(bbuffer);
            header.write(kafkaAccessor, cache, headerVersion);
            body.write(kafkaAccessor, cache, apiVersion);
        }
        bbuffer.flip();
        return bbuffer;
    }

    protected <F extends Frame> void testEncode(ByteBuffer expected, F toBeEncoded, KafkaMessageEncoder<F> encoder) throws Exception {
        assertEquals(expected.limit(), expected.capacity());

        // Encode using our APIS
        ByteBuffer ourBuffer = ByteBuffer.allocate(expected.capacity()).clear();

        ByteBuf out = Unpooled.wrappedBuffer(ourBuffer).resetWriterIndex();
        encoder.encode(null, toBeEncoded, out);

        // Assert that we filled the buffer (since it was supposed to be the exact size)
        assertEquals(ourBuffer.limit(), ourBuffer.capacity());

        // Compare the buffers byte-for-byte
        assertArrayEquals(
                expected.array(), ourBuffer.array(),
                String.format("Expected: %s%nBut was: %s", Arrays.toString(expected.array()), Arrays.toString(ourBuffer.array())));
    }

    public static void assertSameBytes(ByteBuf expect, ByteBuf actual) {
        byte[] expectedBytes = ByteBufUtil.getBytes(expect);
        byte[] actualBytes = ByteBufUtil.getBytes(actual);
        assertArrayEquals(expectedBytes, actualBytes,
                "Expected the buffers to contain the same bytes:\n" +
                        Arrays.toString(expectedBytes) + "\ncf\n" + Arrays.toString(actualBytes));
    }

    public static <H extends ApiMessage, B extends ApiMessage> void exactlyOneFrame_decoded(
                                                                                            short apiVersion,
                                                                                            Function<Short, Short> headerVersionSupplier,
                                                                                            Function<Short, H> headerSupplier,
                                                                                            Supplier<B> bodySupplier,
                                                                                            BiFunction<Short, ByteBuffer, H> headerDeser,
                                                                                            BiFunction<Short, ByteBuffer, B> bodyDeser,
                                                                                            KafkaMessageDecoder decoder,
                                                                                            Class<? extends DecodedFrame> frameClass)
            throws Exception {
        ApiMessage encodedHeader = headerSupplier.apply(apiVersion);

        ApiMessage encodedBody = bodySupplier.get();

        short headerVersion = headerVersionSupplier.apply(apiVersion); // ;
        ByteBuffer akBuffer = serializeUsingKafkaApis(headerVersion, encodedHeader, apiVersion, encodedBody);

        // This is a bit of a hack... the Data classes know about which fields appear in which versions
        // So use Kafka to deserialize the messages we just serialised using Kafka, so that we
        // have objects we can use assertEquals on later
        akBuffer.mark();
        akBuffer.getInt(); // frame size
        var akHeader = headerDeser.apply(headerVersion, akBuffer);
        var akBody = bodyDeser.apply(apiVersion, akBuffer);
        akBuffer.reset();

        ByteBuf akBuf = Unpooled.wrappedBuffer(akBuffer.duplicate()).asReadOnly();

        var messages = new ArrayList<>();

        decoder.decode(null, akBuf, messages);
        assertEquals(akBuf.writerIndex(), akBuf.readerIndex(), "Expect to have read whole buf");

        assertEquals(List.of(frameClass), messageClasses(messages), "Expected a single decoded frame");
        DecodedFrame<?, ?> frame = (DecodedFrame<?, ?>) messages.get(0);
        assertEquals(akHeader, frame.header());
        assertEquals(akBody, frame.body());
    }

    public static <H extends ApiMessage, B extends ApiMessage> void exactlyOneFrame_encoded(
                                                                                            short apiVersion,
                                                                                            Function<Short, Short> headerVersionSupplier,
                                                                                            Function<Short, H> headerSupplier,
                                                                                            Supplier<B> bodySupplier,
                                                                                            KafkaMessageDecoder decoder,
                                                                                            Class<? extends Frame> frameClass)
            throws Exception {

        var encodedHeader = headerSupplier.apply(apiVersion);

        var encodedBody = bodySupplier.get();

        short headerVersion = headerVersionSupplier.apply(apiVersion);
        ByteBuffer akBuffer = serializeUsingKafkaApis(headerVersion, encodedHeader, apiVersion, encodedBody);
        ByteBuf akBuf = Unpooled.wrappedBuffer(akBuffer.duplicate());

        var messages = new ArrayList<>();
        decoder.decode(null, akBuf, messages);
        assertEquals(akBuf.writerIndex(), akBuf.readerIndex(), "Expect to have read whole buf");

        assertEquals(List.of(frameClass), messageClasses(messages), "Expected a single non-decoded frame");
        OpaqueFrame frame = (OpaqueFrame) messages.get(0);
        akBuf.readerIndex(4); // An OpaqueFrame excludes the length of the frame
        assertSameBytes(akBuf, frame.buf());
    }

}
