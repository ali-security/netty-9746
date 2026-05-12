/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.compression;

import static org.junit.Assert.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedByteChannel;
import io.netty.handler.codec.DecoderException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.junit.Test;

/**
 * Tests for {@link JZlibDecoder}, focusing on the maxAllocation behavior that
 * was backported from upstream Netty PR netty/netty#9924 (CVE-2020-11612).
 */
public class JZlibDecoderTest {

    private static final byte[] BYTES_LARGE = new byte[1024 * 1024];

    static {
        Random rand = new Random();
        rand.nextBytes(BYTES_LARGE);
    }

    @Test
    public void testInvalidMaxAllocation() {
        try {
            new JZlibDecoder(-1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void testDecompressWithinMaxAllocation() throws Exception {
        byte[] payload = "Hello, world!".getBytes("UTF-8");
        byte[] compressed = deflate(payload);

        JZlibDecoder decoder = new JZlibDecoder(ZlibWrapper.ZLIB, 1024);
        EmbeddedByteChannel ch = new EmbeddedByteChannel(decoder);
        try {
            ch.writeInbound(Unpooled.wrappedBuffer(compressed));
            ByteBuf out = (ByteBuf) ch.readInbound();
            assertNotNull(out);
            byte[] decompressed = new byte[out.readableBytes()];
            out.readBytes(decompressed);
            assertArrayEquals(payload, decompressed);
            assertFalse(decoder.isClosed());
        } finally {
            ch.finish();
        }
    }

    @Test
    public void testMaxAllocation() throws Exception {
        int maxAllocation = 1024;
        JZlibDecoder decoder = new JZlibDecoder(ZlibWrapper.ZLIB, maxAllocation);
        EmbeddedByteChannel ch = new EmbeddedByteChannel(decoder);

        try {
            ch.writeInbound(Unpooled.wrappedBuffer(deflate(BYTES_LARGE)));
            fail("decompressed size > maxAllocation, so should have thrown exception");
        } catch (DecoderException e) {
            Throwable cause = e.getCause();
            assertTrue("expected CompressionException but was: " + cause,
                    cause instanceof CompressionException);
            assertTrue(cause.getMessage().startsWith("Decompression buffer has reached maximum size"));
            assertTrue("decoder should be marked closed after exhaustion", decoder.isClosed());
        }
    }

    private static byte[] deflate(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DeflaterOutputStream stream = new DeflaterOutputStream(out, new Deflater());
        stream.write(bytes);
        stream.close();
        return out.toByteArray();
    }
}
