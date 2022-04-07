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
package io.strimzi.kproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LoggingHandler;
import io.strimzi.kproxy.codec.Correlation;
import io.strimzi.kproxy.codec.KafkaRequestEncoder;
import io.strimzi.kproxy.codec.KafkaResponseDecoder;
import io.strimzi.kproxy.interceptor.InterceptorProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class KafkaProxyFrontendHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LogManager.getLogger(KafkaProxyFrontendHandler.class);

    private final String remoteHost;
    private final int remotePort;
    private final Map<Integer, Correlation> correlation;
    private final InterceptorProvider interceptorProvider;
    private final boolean logNetwork;
    private final boolean logFrames;
    private volatile Channel outboundChannel;
    private KafkaProxyBackendHandler backendHandler;

    public KafkaProxyFrontendHandler(String remoteHost, int remotePort,
                                     Map<Integer, Correlation> correlation,
                                     InterceptorProvider interceptorProvider, boolean logNetwork, boolean logFrames) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.correlation = correlation;
        this.interceptorProvider = interceptorProvider;
        this.logNetwork = logNetwork;
        this.logFrames = logFrames;
    }



    @Override
    public void channelWritabilityChanged(final ChannelHandlerContext ctx) throws Exception {
        super.channelWritabilityChanged(ctx);
        // this is key to propagate back-pressure changes
        if (backendHandler != null) {
            backendHandler.inboundChannelWritabilityChanged(ctx);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        LOGGER.trace("Channel active {}", ctx);
        final Channel inboundChannel = ctx.channel();

        // Start the upstream connection attempt.
        Bootstrap b = new Bootstrap();
        backendHandler = new KafkaProxyBackendHandler(ctx);
        b.group(inboundChannel.eventLoop())
            .channel(ctx.channel().getClass())
                .handler(backendHandler)
            .option(ChannelOption.AUTO_READ, true);
        LOGGER.trace("Connecting to outbound {}:{}", remoteHost, remotePort);
        ChannelFuture connectFuture = b.connect(remoteHost, remotePort);
        outboundChannel = connectFuture.channel();
        ChannelPipeline pipeline = outboundChannel.pipeline();
        List<ChannelHandler> handlers = new ArrayList<>();
        if (logNetwork) {
            handlers.add(new LoggingHandler("backend-network"));
        }
        handlers.add(new KafkaRequestEncoder());
        handlers.add(new KafkaResponseDecoder(correlation));
        //handlers.add(new ApiVersionsResponseHandler());
        var e = interceptorProvider.backendHandlers();
        if (e != null) {
            handlers.addAll(e);
        }
        if (logFrames) {
            handlers.add(new LoggingHandler("backend-application"));
        }
        Collections.reverse(handlers);
        for (var handler : handlers) {
            pipeline.addFirst(handler);
        }
        connectFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (future.isSuccess()) {
                    LOGGER.trace("Outbound connect complete ({}), register interest to read on inbound channel {}", outboundChannel.localAddress(), inboundChannel);
                    // connection complete start to read first data
                    inboundChannel.read();
                } else {
                    // Close the connection if the connection attempt has failed.
                    LOGGER.trace("Outbound connect error, closing inbound channel", future.cause());
                    inboundChannel.close();
                }
            }
        });
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) {
        LOGGER.trace("Completed read on inbound channel: {}", msg);
        if (outboundChannel.isActive()) {
            LOGGER.trace("Outbound writable: {}", outboundChannel.isWritable());
            LOGGER.trace("Outbound bytesBeforeUnwritable: {}", outboundChannel.bytesBeforeUnwritable());
            LOGGER.trace("Outbound config: {}", outboundChannel.config());
            LOGGER.trace("Outbound is active, writing and flushing {}", msg);
            outboundChannel.writeAndFlush(msg).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                    if (future.isSuccess()) {
                        LOGGER.trace("Outbound wrote and flushed");
                        // was able to flush out data, start to read the next chunk
                        ctx.channel().read();
                    } else {
                        LOGGER.trace("Outbound wrote and flushed error, closing channel", future.cause());
                        future.channel().close();
                    }
                }
            });
        } else {
            LOGGER.trace("Outbound is not active");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (outboundChannel != null) {
            closeOnFlush(outboundChannel);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        closeOnFlush(ctx.channel());
    }

    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    static void closeOnFlush(Channel ch) {
        if (ch.isActive()) {
           ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
       }
   }

}