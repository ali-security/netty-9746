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

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedByteChannel;
import io.netty.handler.codec.CodecException;
import io.netty.util.CharsetUtil;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpMessageDecoderTest {

    @Test
    public void testHeaderWithNoValueAndMissingColon() {
        EmbeddedByteChannel channel = new EmbeddedByteChannel(
            new HttpRequestDecoder());
        String requestStr = "GET /some/path HTTP/1.1\r\n" +
                "Content-Length: 0\r\n" +
                "Host:\r\n" +
                "netty.io\r\n\r\n";

        try {
            channel.writeInbound(Unpooled.copiedBuffer(requestStr, CharsetUtil.US_ASCII));
            fail();
        } catch (CodecException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }
}
