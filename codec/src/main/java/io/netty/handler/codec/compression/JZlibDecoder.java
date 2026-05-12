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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnsafeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.internal.jzlib.JZlib;
import io.netty.util.internal.jzlib.ZStream;

public class JZlibDecoder extends ZlibDecoder {

    private final ZStream z = new ZStream();
    private byte[] dictionary;
    private volatile boolean finished;

    /**
     * Creates a new instance with the default wrapper ({@link ZlibWrapper#ZLIB}).
     *
     * @throws CompressionException if failed to initialize zlib
     */
    public JZlibDecoder() {
        this(ZlibWrapper.ZLIB, 0);
    }

    /**
     * Creates a new instance with the default wrapper ({@link ZlibWrapper#ZLIB})
     * and specified maximum buffer allocation.
     *
     * @param maxAllocation
     *          Maximum size of the decompression buffer. Must be &gt;= 0.
     *          If zero, maximum size is decided by the {@link ByteBufAllocator}.
     *
     * @throws CompressionException if failed to initialize zlib
     */
    public JZlibDecoder(int maxAllocation) {
        this(ZlibWrapper.ZLIB, maxAllocation);
    }

    /**
     * Creates a new instance with the specified wrapper.
     *
     * @throws CompressionException if failed to initialize zlib
     */
    public JZlibDecoder(ZlibWrapper wrapper) {
        this(wrapper, 0);
    }

    /**
     * Creates a new instance with the specified wrapper and maximum buffer allocation.
     *
     * @param maxAllocation
     *          Maximum size of the decompression buffer. Must be &gt;= 0.
     *          If zero, maximum size is decided by the {@link ByteBufAllocator}.
     *
     * @throws CompressionException if failed to initialize zlib
     */
    public JZlibDecoder(ZlibWrapper wrapper, int maxAllocation) {
        super(maxAllocation);

        if (wrapper == null) {
            throw new NullPointerException("wrapper");
        }

        int resultCode = z.inflateInit(ZlibUtil.convertWrapperType(wrapper));
        if (resultCode != JZlib.Z_OK) {
            ZlibUtil.fail(z, "initialization failure", resultCode);
        }
    }

    /**
     * Creates a new instance with the specified preset dictionary. The wrapper
     * is always {@link ZlibWrapper#ZLIB} because it is the only format that
     * supports the preset dictionary.
     *
     * @throws CompressionException if failed to initialize zlib
     */
    public JZlibDecoder(byte[] dictionary) {
        this(dictionary, 0);
    }

    /**
     * Creates a new instance with the specified preset dictionary and maximum buffer allocation.
     * The wrapper is always {@link ZlibWrapper#ZLIB} because it is the only format that
     * supports the preset dictionary.
     *
     * @param maxAllocation
     *          Maximum size of the decompression buffer. Must be &gt;= 0.
     *          If zero, maximum size is decided by the {@link ByteBufAllocator}.
     *
     * @throws CompressionException if failed to initialize zlib
     */
    public JZlibDecoder(byte[] dictionary, int maxAllocation) {
        super(maxAllocation);

        if (dictionary == null) {
            throw new NullPointerException("dictionary");
        }
        this.dictionary = dictionary;

        int resultCode;
        resultCode = z.inflateInit(JZlib.W_ZLIB);
        if (resultCode != JZlib.Z_OK) {
            ZlibUtil.fail(z, "initialization failure", resultCode);
        }
    }

    /**
     * Returns {@code true} if and only if the end of the compressed stream
     * has been reached.
     */
    @Override
    public boolean isClosed() {
        return finished;
    }

    @Override
    public void decode(
            ChannelHandlerContext ctx,
            ByteBuf in, ByteBuf out) throws Exception {

        if (!in.readable()) {
            return;
        }

        ByteBuf decompressed = null;
        try {
            // Configure input.
            int inputLength = in.readableBytes();
            boolean inHasArray = in.hasArray();
            z.avail_in = inputLength;
            if (inHasArray) {
                z.next_in = in.array();
                z.next_in_index = in.arrayOffset() + in.readerIndex();
            } else {
                byte[] array = new byte[inputLength];
                in.readBytes(array);
                z.next_in = array;
                z.next_in_index = 0;
            }
            int oldNextInIndex = z.next_in_index;

            // Configure output - use an internal decompression buffer so we can enforce maxAllocation.
            decompressed = prepareDecompressBuffer(ctx, null, inputLength << 1);

            try {
                loop: for (;;) {
                    decompressed = prepareDecompressBuffer(ctx, decompressed, z.avail_in << 1);
                    z.avail_out = decompressed.writableBytes();
                    z.next_out = decompressed.array();
                    z.next_out_index = decompressed.arrayOffset() + decompressed.writerIndex();
                    int oldNextOutIndex = z.next_out_index;

                    // Decompress 'in' into 'decompressed'
                    int resultCode = z.inflate(JZlib.Z_SYNC_FLUSH);
                    int outputLength = z.next_out_index - oldNextOutIndex;
                    if (outputLength > 0) {
                        decompressed.writerIndex(decompressed.writerIndex() + outputLength);
                    }

                    switch (resultCode) {
                    case JZlib.Z_NEED_DICT:
                        if (dictionary == null) {
                            ZlibUtil.fail(z, "decompression failure", resultCode);
                        } else {
                            resultCode = z.inflateSetDictionary(dictionary, dictionary.length);
                            if (resultCode != JZlib.Z_OK) {
                                ZlibUtil.fail(z, "failed to set the dictionary", resultCode);
                            }
                        }
                        break;
                    case JZlib.Z_STREAM_END:
                        finished = true; // Do not decode anymore.
                        z.inflateEnd();
                        break loop;
                    case JZlib.Z_OK:
                        break;
                    case JZlib.Z_BUF_ERROR:
                        if (z.avail_in <= 0) {
                            break loop;
                        }
                        break;
                    default:
                        ZlibUtil.fail(z, "decompression failure", resultCode);
                    }
                }

                if (decompressed.readable()) {
                    out.writeBytes(decompressed);
                }
            } finally {
                if (inHasArray) {
                    in.skipBytes(z.next_in_index - oldNextInIndex);
                }
            }
        } finally {
            if (decompressed != null) {
                ((UnsafeByteBuf) decompressed).free();
            }
            // Deference the external references explicitly to tell the VM that
            // the allocated byte arrays are temporary so that the call stack
            // can be utilized.
            // I'm not sure if the modern VMs do this optimization though.
            z.next_in = null;
            z.next_out = null;
        }
    }

    @Override
    protected void decompressionBufferExhausted(ByteBuf buffer) {
        finished = true;
    }
}
