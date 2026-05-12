/*
 * Copyright 2024 The Netty Project
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
package io.netty.handler.codec.http.multipart;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpChunk;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpTransferEncoding;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.IncompatibleDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.TooLongFormFieldException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.TooManyFormFieldsException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class HttpPostRequestDecoderTest {

    private static HttpRequest newChunkedRequest() {
        HttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        req.setTransferEncoding(HttpTransferEncoding.CHUNKED);
        return req;
    }

    @Test
    public void testTooManyFormFieldsPostStandardDecoder() throws Exception {
        HttpRequest req = newChunkedRequest();

        int maxFields = 8;
        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(req, maxFields, -1);

        StringBuilder body = new StringBuilder();
        for (int i = 0; i <= maxFields; i++) {
            body.append("k").append(i).append("=v&");
        }

        try {
            decoder.offer(new DefaultHttpChunk(Unpooled.wrappedBuffer(body.toString().getBytes())));
            fail();
        } catch (ErrorDataDecoderException e) {
            assertEquals(TooManyFormFieldsException.class, e.getClass());
        }
    }

    @Test
    public void testStandardDecoderAcceptsUpToMaxFields() throws Exception {
        HttpRequest req = newChunkedRequest();

        int maxFields = 8;
        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(req, maxFields, -1);

        StringBuilder body = new StringBuilder();
        for (int i = 0; i < maxFields; i++) {
            body.append("k").append(i).append("=v&");
        }

        decoder.offer(new DefaultHttpChunk(Unpooled.wrappedBuffer(body.toString().getBytes())));
    }

    @Test
    public void testTooManyFormFieldsPostMultipartDecoder() throws Exception {
        HttpRequest req = newChunkedRequest();
        req.addHeader("Content-Type", "multipart/form-data; boundary=be38b42a9ad2713f");

        int maxFields = 4;
        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(req, maxFields, -1);

        StringBuilder body = new StringBuilder();
        body.append("--be38b42a9ad2713f\r\n");
        for (int i = 0; i <= maxFields; i++) {
            body.append("Content-Disposition: form-data; name=\"k").append(i).append("\"\r\n")
                .append("\r\n")
                .append("v").append(i).append("\r\n")
                .append("--be38b42a9ad2713f");
            if (i == maxFields) {
                body.append("--\r\n");
            } else {
                body.append("\r\n");
            }
        }

        try {
            decoder.offer(new DefaultHttpChunk(Unpooled.wrappedBuffer(body.toString().getBytes())));
            fail();
        } catch (ErrorDataDecoderException e) {
            assertEquals(TooManyFormFieldsException.class, e.getClass());
        }
    }

    @Test
    public void testTooLongFormFieldStandardDecoder() throws Exception {
        HttpRequest req = newChunkedRequest();

        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(req, -1, 16 * 1024);

        try {
            decoder.offer(new DefaultHttpChunk(Unpooled.wrappedBuffer(new byte[16 * 1024 + 1])));
            fail();
        } catch (ErrorDataDecoderException e) {
            assertEquals(TooLongFormFieldException.class, e.getClass());
        }
    }

    @Test
    public void testFieldGreaterThanMaxBufferedBytesStandardDecoder() throws Exception {
        HttpRequest req = newChunkedRequest();

        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(req, -1, 6);

        decoder.offer(new DefaultHttpChunk(Unpooled.wrappedBuffer("foo=bar".getBytes())));
    }

    @Test
    public void testTooLongFormFieldMultipartDecoder() throws Exception {
        HttpRequest req = newChunkedRequest();
        req.addHeader("Content-Type", "multipart/form-data; boundary=be38b42a9ad2713f");

        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(req, -1, 16 * 1024);

        try {
            decoder.offer(new DefaultHttpChunk(Unpooled.wrappedBuffer(new byte[16 * 1024 + 1])));
            fail();
        } catch (ErrorDataDecoderException e) {
            assertEquals(TooLongFormFieldException.class, e.getClass());
        }
    }

    @Test
    public void testFieldGreaterThanMaxBufferedBytesMultipartDecoder() throws Exception {
        HttpRequest req = newChunkedRequest();
        req.addHeader("Content-Type", "multipart/form-data; boundary=be38b42a9ad2713f");

        byte[] bodyBytes = ("--be38b42a9ad2713f\r\n" +
                "Content-Disposition: form-data; name=\"title\"\r\n" +
                "Content-Length: 10\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n" +
                "\r\n" +
                "bar-stream\r\n" +
                "--be38b42a9ad2713f--\r\n").getBytes();

        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(req, -1, bodyBytes.length - 1);

        decoder.offer(new DefaultHttpChunk(Unpooled.wrappedBuffer(bodyBytes)));
    }

    @Test(expected = IncompatibleDataDecoderException.class)
    public void testNonPostRequestRejected() throws Exception {
        HttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        new HttpPostRequestDecoder(req);
    }
}
