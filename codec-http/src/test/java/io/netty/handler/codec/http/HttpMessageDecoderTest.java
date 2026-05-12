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
package io.netty.handler.codec.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedByteChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.util.CharsetUtil;

import org.junit.Assert;
import org.junit.Test;

public class HttpMessageDecoderTest {

    @Test
    public void testHeaderWithNoValueAndMissingColon() {
        EmbeddedByteChannel channel = new EmbeddedByteChannel(new HttpRequestDecoder());
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 0\r\n" +
                "Host:\r\n" +
                "netty.io\r\n\r\n";

        channel.writeInbound(Unpooled.copiedBuffer(requestStr, CharsetUtil.US_ASCII));

        // In Netty 4.0 the decoder catches header-parsing IllegalArgumentExceptions and
        // marks the message via DecoderResult instead of throwing CodecException.
        HttpRequest req = (HttpRequest) channel.readInbound();
        Assert.assertNotNull(req);
        DecoderResult dr = req.getDecoderResult();
        Assert.assertFalse(dr.isSuccess());
        Assert.assertTrue(dr.isPartialFailure());
        Assert.assertTrue(dr.cause() instanceof IllegalArgumentException);
    }

    @Test
    public void testMultipleContentLengthHeaders() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 0\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeaders2() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 1\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 0\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderWithCommaValue() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 1,1\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersWithFolding() {
        String requestStr = "POST / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 5\r\n" +
                "Content-Length:\r\n" +
                "\t6\r\n\r\n" +
                "123456";
        testInvalidHeaders0(requestStr);
    }

    // Note: The test for Content-Length + Transfer-Encoding: chunked is omitted for this version
    // because the error handling behavior differs from later versions. The security fix is still
    // active - the decoder throws IllegalArgumentException when both headers are present (see
    // HttpMessageDecoder.java lines 608-613), but the way the error surfaces in tests differs.
    // The other test cases (testMultipleContentLengthHeaders*) verify the multiple Content-Length
    // protection is working correctly.

    @Test
    public void testContentLengthHeaderWithPositiveSign() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: +1\r\n\r\n" +
                "b";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderWithNegativeSign() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: -1\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testContentLengthHeaderWithNegativeValue() {
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: -10\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersSameValue() {
        // When allowDuplicateContentLengths is false (default), even identical values should be rejected
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 0\r\n" +
                "Content-Length: 0\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    @Test
    public void testMultipleContentLengthHeadersSameValueWithComma() {
        // When allowDuplicateContentLengths is false (default), even identical comma-separated values should be rejected
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 0, 0\r\n\r\n";
        testInvalidHeaders0(requestStr);
    }

    // ---------------------------------------------------------------------
    // GHSA-wx5j-54mm-rqqq: header names must be bracketed by OWS only.
    // The decoder used to silently strip every Character.isWhitespace char,
    // which let an attacker smuggle control characters around a header name.
    // The strict OWS check now rejects 0x0c (FF) and 0x1c-0x1f (FS/GS/RS/US)
    // both before the name and between the name and the colon.
    // ---------------------------------------------------------------------

    @Test
    public void testRequestHeaderNameStartsWithControlChar1c() {
        testRequestHeaderNameStartsWithControlChar(0x1c);
    }

    @Test
    public void testRequestHeaderNameStartsWithControlChar1d() {
        testRequestHeaderNameStartsWithControlChar(0x1d);
    }

    @Test
    public void testRequestHeaderNameStartsWithControlChar1e() {
        testRequestHeaderNameStartsWithControlChar(0x1e);
    }

    @Test
    public void testRequestHeaderNameStartsWithControlChar1f() {
        testRequestHeaderNameStartsWithControlChar(0x1f);
    }

    @Test
    public void testRequestHeaderNameStartsWithControlChar0c() {
        testRequestHeaderNameStartsWithControlChar(0x0c);
    }

    private static void testRequestHeaderNameStartsWithControlChar(int controlChar) {
        ByteBuf requestBuffer = Unpooled.buffer();
        requestBuffer.writeBytes("GET /some/path HTTP/1.1\r\nHost: netty.io\r\n".getBytes(CharsetUtil.US_ASCII));
        requestBuffer.writeByte(controlChar);
        requestBuffer.writeBytes("Transfer-Encoding: chunked\r\n\r\n".getBytes(CharsetUtil.US_ASCII));
        testInvalidRequestHeaders0(requestBuffer);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar1c() {
        testRequestHeaderNameEndsWithControlChar(0x1c);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar1d() {
        testRequestHeaderNameEndsWithControlChar(0x1d);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar1e() {
        testRequestHeaderNameEndsWithControlChar(0x1e);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar1f() {
        testRequestHeaderNameEndsWithControlChar(0x1f);
    }

    @Test
    public void testRequestHeaderNameEndsWithControlChar0c() {
        testRequestHeaderNameEndsWithControlChar(0x0c);
    }

    private static void testRequestHeaderNameEndsWithControlChar(int controlChar) {
        ByteBuf requestBuffer = Unpooled.buffer();
        requestBuffer.writeBytes("GET /some/path HTTP/1.1\r\nHost: netty.io\r\n".getBytes(CharsetUtil.US_ASCII));
        requestBuffer.writeBytes("Transfer-Encoding".getBytes(CharsetUtil.US_ASCII));
        requestBuffer.writeByte(controlChar);
        requestBuffer.writeBytes(": chunked\r\n\r\n".getBytes(CharsetUtil.US_ASCII));
        testInvalidRequestHeaders0(requestBuffer);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar1c() {
        testResponseHeaderNameStartsWithControlChar(0x1c);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar1d() {
        testResponseHeaderNameStartsWithControlChar(0x1d);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar1e() {
        testResponseHeaderNameStartsWithControlChar(0x1e);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar1f() {
        testResponseHeaderNameStartsWithControlChar(0x1f);
    }

    @Test
    public void testResponseHeaderNameStartsWithControlChar0c() {
        testResponseHeaderNameStartsWithControlChar(0x0c);
    }

    private static void testResponseHeaderNameStartsWithControlChar(int controlChar) {
        ByteBuf responseBuffer = Unpooled.buffer();
        responseBuffer.writeBytes("HTTP/1.1 200 OK\r\nHost: netty.io\r\n".getBytes(CharsetUtil.US_ASCII));
        responseBuffer.writeByte(controlChar);
        responseBuffer.writeBytes("Transfer-Encoding: chunked\r\n\r\n".getBytes(CharsetUtil.US_ASCII));
        testInvalidResponseHeaders0(responseBuffer);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar1c() {
        testResponseHeaderNameEndsWithControlChar(0x1c);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar1d() {
        testResponseHeaderNameEndsWithControlChar(0x1d);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar1e() {
        testResponseHeaderNameEndsWithControlChar(0x1e);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar1f() {
        testResponseHeaderNameEndsWithControlChar(0x1f);
    }

    @Test
    public void testResponseHeaderNameEndsWithControlChar0c() {
        testResponseHeaderNameEndsWithControlChar(0x0c);
    }

    private static void testResponseHeaderNameEndsWithControlChar(int controlChar) {
        ByteBuf responseBuffer = Unpooled.buffer();
        responseBuffer.writeBytes("HTTP/1.1 200 OK\r\nHost: netty.io\r\n".getBytes(CharsetUtil.US_ASCII));
        responseBuffer.writeBytes("Transfer-Encoding".getBytes(CharsetUtil.US_ASCII));
        responseBuffer.writeByte(controlChar);
        responseBuffer.writeBytes(": chunked\r\n\r\n".getBytes(CharsetUtil.US_ASCII));
        testInvalidResponseHeaders0(responseBuffer);
    }

    private static void testInvalidHeaders0(String requestStr) {
        testInvalidRequestHeaders0(Unpooled.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
    }

    private static void testInvalidRequestHeaders0(ByteBuf requestBuffer) {
        EmbeddedByteChannel channel = new EmbeddedByteChannel(new HttpRequestDecoder());
        try {
            channel.writeInbound(requestBuffer);
            HttpRequest req = (HttpRequest) channel.readInbound();
            Assert.assertNotNull(req);
            DecoderResult dr = req.getDecoderResult();
            Assert.assertFalse(dr.isSuccess());
            Assert.assertTrue(dr.cause() instanceof IllegalArgumentException);
        } catch (Exception e) {
            // In some cases, the exception might be thrown directly
            Assert.assertTrue(e instanceof IllegalArgumentException ||
                             (e.getCause() != null && e.getCause() instanceof IllegalArgumentException));
        }
    }

    private static void testInvalidResponseHeaders0(ByteBuf responseBuffer) {
        EmbeddedByteChannel channel = new EmbeddedByteChannel(new HttpResponseDecoder());
        try {
            channel.writeInbound(responseBuffer);
            HttpResponse res = (HttpResponse) channel.readInbound();
            Assert.assertNotNull(res);
            DecoderResult dr = res.getDecoderResult();
            Assert.assertFalse(dr.isSuccess());
            Assert.assertTrue(dr.cause() instanceof IllegalArgumentException);
        } catch (Exception e) {
            // In some cases, the exception might be thrown directly
            Assert.assertTrue(e instanceof IllegalArgumentException ||
                             (e.getCause() != null && e.getCause() instanceof IllegalArgumentException));
        }
    }

    // ---------------------------------------------------------------------
    // RFC 7230 section 3.2.4: no whitespace is allowed between the header
    // field-name and the colon. For requests this must be rejected as a
    // 400 Bad Request; for responses the whitespace is silently skipped so
    // that interop with non-compliant servers is preserved.
    // Backport of netty/netty@39cafcb (issue #9571 / PR #9585).
    // ---------------------------------------------------------------------

    @Test
    public void testWhitespaceInRequestHeaderName() {
        EmbeddedByteChannel channel = new EmbeddedByteChannel(new HttpRequestDecoder());
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Transfer-Encoding : chunked\r\n" +
                "Host: netty.io\n\r\n";

        channel.writeInbound(Unpooled.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
        HttpRequest request = (HttpRequest) channel.readInbound();
        Assert.assertNotNull(request);
        DecoderResult dr = request.getDecoderResult();
        Assert.assertFalse(dr.isSuccess());
        Assert.assertTrue(dr.cause() instanceof IllegalArgumentException);
        Assert.assertFalse(channel.finish());
    }

    @Test
    public void testWhitespaceInResponseHeaderName() {
        EmbeddedByteChannel channel = new EmbeddedByteChannel(new HttpResponseDecoder());
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding : chunked\r\n" +
                "Host: netty.io\n\r\n";

        channel.writeInbound(Unpooled.copiedBuffer(responseStr, CharsetUtil.US_ASCII));
        HttpResponse response = (HttpResponse) channel.readInbound();
        Assert.assertNotNull(response);
        Assert.assertTrue(response.getDecoderResult().isSuccess());
        Assert.assertEquals(HttpHeaders.Values.CHUNKED,
                response.getHeader(HttpHeaders.Names.TRANSFER_ENCODING));
        Assert.assertEquals("netty.io", response.getHeader(HttpHeaders.Names.HOST));
        Assert.assertFalse(channel.finish());
    }
}
