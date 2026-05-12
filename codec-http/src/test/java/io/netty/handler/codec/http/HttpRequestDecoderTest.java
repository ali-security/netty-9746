/*
 * Copyright 2020 The Netty Project
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
package io.netty.handler.codec.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedByteChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.CharsetUtil;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Backport of the tests added in netty/netty#10112 that exercise the
 * {@code maxInitialLineLength} budget for leading control characters.
 *
 * <p>Without the fix, an attacker could keep the decoder busy indefinitely by sending an
 * unbounded run of CRLF or whitespace bytes before the initial line because
 * {@link HttpMessageDecoder} skipped control characters without enforcing any limit
 * (see https://github.com/netty/netty/issues/10111).</p>
 */
public class HttpRequestDecoderTest {

    @Test
    public void testTooLargeInitialLineWithWSOnly() {
        testTooLargeInitialLineWithControlCharsOnly("                    ");
    }

    @Test
    public void testTooLargeInitialLineWithCRLFOnly() {
        testTooLargeInitialLineWithControlCharsOnly("\r\n\r\n\r\n\r\n\r\n\r\n\r\n\r\n");
    }

    private static void testTooLargeInitialLineWithControlCharsOnly(String controlChars) {
        EmbeddedByteChannel channel = new EmbeddedByteChannel(new HttpRequestDecoder(15, 1024, 1024));
        // The initial line itself fits in 15 bytes ("GET / HTTP/1.1") but together with the leading
        // control characters it must exceed the limit and be rejected.
        String requestStr = controlChars + "GET / HTTP/1.1\r\n" +
                "Host: localhost1\r\n\r\n";

        channel.writeInbound(Unpooled.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
        HttpRequest request = (HttpRequest) channel.readInbound();
        assertNotNull(request);
        DecoderResult dr = request.getDecoderResult();
        assertFalse(dr.isSuccess());
        assertTrue("expected TooLongFrameException but was: " + dr.cause(),
                dr.cause() instanceof TooLongFrameException);
        assertFalse(channel.finish());
    }

    @Test
    public void testInitialLineWithLeadingControlChars() {
        // A small amount of leading control characters that fits within the default
        // maxInitialLineLength must still parse cleanly: this guards against accidentally
        // rejecting valid (if oddly framed) requests after the DoS fix.
        EmbeddedByteChannel channel = new EmbeddedByteChannel(new HttpRequestDecoder());
        String crlf = "\r\n";
        String request = crlf + "GET /some/path HTTP/1.1" + crlf +
                "Host: localhost" + crlf + crlf;
        channel.writeInbound(Unpooled.copiedBuffer(request, CharsetUtil.US_ASCII));
        HttpRequest req = (HttpRequest) channel.readInbound();
        assertNotNull(req);
        assertTrue(req.getDecoderResult().isSuccess());
        assertEquals(HttpMethod.GET, req.getMethod());
        assertEquals("/some/path", req.getUri());
        assertEquals(HttpVersion.HTTP_1_1, req.getProtocolVersion());
        assertFalse(channel.finish());
    }
}
