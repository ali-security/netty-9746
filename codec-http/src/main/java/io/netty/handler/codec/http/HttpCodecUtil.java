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

import java.util.List;

/**
 * A utility class mainly for use with HTTP codec classes
 */
final class HttpCodecUtil {

    /**
     * Validates the name of a header
     *
     * @param headerName The header name being validated
     */
    static void validateHeaderName(String headerName) {
        //Check to see if the name is null
        if (headerName == null) {
            throw new NullPointerException("Header names cannot be null");
        }
        //Go through each of the characters in the name
        for (int index = 0; index < headerName.length(); index ++) {
            //Actually get the character
            char character = headerName.charAt(index);

            //Check to see if the character is not an ASCII character
            if (character > 127) {
                throw new IllegalArgumentException(
                    "Header name cannot contain non-ASCII characters: " + headerName);
            }

            //Check for prohibited characters.
            switch (character) {
            // GHSA-wx5j-54mm-rqqq: explicitly reject the C1 control characters
            // 0x1c-0x1f so they cannot be smuggled into header names.
            case 0x1c: case 0x1d: case 0x1e: case 0x1f:
            case '\t': case '\n': case 0x0b: case '\f': case '\r':
            case ' ':  case ',':  case ':':  case ';':  case '=':
                throw new IllegalArgumentException(
                        "Header name cannot contain the following prohibited characters: " +
                        "=,;: \\t\\r\\n\\v\\f\\x1c\\x1d\\x1e\\x1f: " + headerName);
            }
        }
    }

    /**
     * Validates the specified header value
     *
     * @param value The value being validated
     */
    static void validateHeaderValue(String headerValue) {
        //Check to see if the value is null
        if (headerValue == null) {
            throw new NullPointerException("Header values cannot be null");
        }

        /*
         * Set up the state of the validation
         *
         * States are as follows:
         *
         * 0: Previous character was neither CR nor LF
         * 1: The previous character was CR
         * 2: The previous character was LF
         */
        int state = 0;

        //Start looping through each of the character

        for (int index = 0; index < headerValue.length(); index ++) {
            char character = headerValue.charAt(index);

            //Check the absolutely prohibited characters.
            switch (character) {
            case 0x0b: // Vertical tab
                throw new IllegalArgumentException(
                        "Header value contains a prohibited character '\\v': " + headerValue);
            case '\f':
                throw new IllegalArgumentException(
                        "Header value contains a prohibited character '\\f': " + headerValue);
            }

            // Check the CRLF (HT | SP) pattern
            switch (state) {
            case 0:
                switch (character) {
                case '\r':
                    state = 1;
                    break;
                case '\n':
                    state = 2;
                    break;
                }
                break;
            case 1:
                switch (character) {
                case '\n':
                    state = 2;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Only '\\n' is allowed after '\\r': " + headerValue);
                }
                break;
            case 2:
                switch (character) {
                case '\t': case ' ':
                    state = 0;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Only ' ' and '\\t' are allowed after '\\n': " + headerValue);
                }
            }
        }

        if (state != 0) {
            throw new IllegalArgumentException(
                    "Header value must not end with '\\r' or '\\n':" + headerValue);
        }
    }

    /**
     * Checks to see if the transfer encoding in a specified {@link HttpMessage} is chunked
     *
     * @param message The message to check
     * @return True if transfer encoding is chunked, otherwise false
     */
    static boolean isTransferEncodingChunked(HttpMessage message) {
        List<String> transferEncodingHeaders = message.getHeaders(HttpHeaders.Names.TRANSFER_ENCODING);
        if (transferEncodingHeaders.isEmpty()) {
            return false;
        }

        for (String value: transferEncodingHeaders) {
            if (value.equalsIgnoreCase(HttpHeaders.Values.CHUNKED)) {
                return true;
            }
        }
        return false;
    }

    static void removeTransferEncodingChunked(HttpMessage m) {
        List<String> values = m.getHeaders(HttpHeaders.Names.TRANSFER_ENCODING);
        values.remove(HttpHeaders.Values.CHUNKED);
        if (values.isEmpty()) {
            m.removeHeader(HttpHeaders.Names.TRANSFER_ENCODING);
        } else {
            m.setHeader(HttpHeaders.Names.TRANSFER_ENCODING, values);
        }
    }

    static boolean isContentLengthSet(HttpMessage m) {
        List<String> contentLength = m.getHeaders(HttpHeaders.Names.CONTENT_LENGTH);
        return !contentLength.isEmpty();
    }

    /**
     * Validates, and optionally extracts the content length from headers. This method is not intended for
     * general use, but is here to be shared between HTTP/1 and HTTP/2 parsing.
     *
     * @param contentLengthFields the content-length header fields.
     * @param isHttp10OrEarlier {@code true} if we are handling HTTP/1.0 or earlier
     * @param allowDuplicateContentLengths {@code true}  if multiple, identical-value content lengths should be allowed.
     * @return the normalized content length from the headers or {@code -1} if the fields were empty.
     * @throws IllegalArgumentException if the content-length fields are not valid
     */
    static long normalizeAndGetContentLength(
            List<String> contentLengthFields, boolean isHttp10OrEarlier,
            boolean allowDuplicateContentLengths) {
        if (contentLengthFields.isEmpty()) {
            return -1;
        }

        // Guard against multiple Content-Length headers as stated in
        // https://tools.ietf.org/html/rfc7230#section-3.3.2:
        //
        // If a message is received that has multiple Content-Length header
        //   fields with field-values consisting of the same decimal value, or a
        //   single Content-Length header field with a field value containing a
        //   list of identical decimal values (e.g., "Content-Length: 42, 42"),
        //   indicating that duplicate Content-Length header fields have been
        //   generated or combined by an upstream message processor, then the
        //   recipient MUST either reject the message as invalid or replace the
        //   duplicated field-values with a single valid Content-Length field
        //   containing that decimal value prior to determining the message body
        //   length or forwarding the message.
        String firstField = contentLengthFields.get(0);
        boolean multipleContentLengths =
                contentLengthFields.size() > 1 || firstField.indexOf(',') >= 0;

        if (multipleContentLengths && !isHttp10OrEarlier) {
            if (allowDuplicateContentLengths) {
                // Find and enforce that all Content-Length values are the same
                String firstValue = null;
                for (String field : contentLengthFields) {
                    String[] tokens = field.split(",", -1);
                    for (String token : tokens) {
                        String trimmed = token.trim();
                        if (firstValue == null) {
                            firstValue = trimmed;
                        } else if (!trimmed.equals(firstValue)) {
                            throw new IllegalArgumentException(
                                    "Multiple Content-Length values found: " + contentLengthFields);
                        }
                    }
                }
                // Replace the duplicated field-values with a single valid Content-Length field
                firstField = firstValue;
            } else {
                // Reject the message as invalid
                throw new IllegalArgumentException(
                        "Multiple Content-Length values found: " + contentLengthFields);
            }
        }
        // Ensure we not allow sign as part of the content-length:
        // See https://github.com/squid-cache/squid/security/advisories/GHSA-qf3v-rc95-96j5
        if (!Character.isDigit(firstField.charAt(0))) {
            // Reject the message as invalid
            throw new IllegalArgumentException(
                    "Content-Length value is not a number: " + firstField);
        }
        try {
            final long value = Long.parseLong(firstField);
            if (value < 0) {
                // Reject the message as invalid
                throw new IllegalArgumentException(
                        "Content-Length value must be >=0: " + value);
            }
            return value;
        } catch (NumberFormatException e) {
            // Reject the message as invalid
            throw new IllegalArgumentException(
                    "Content-Length value is not a number: " + firstField, e);
        }
    }

    /**
     * A constructor to ensure that instances of this class are never made
     */
    private HttpCodecUtil() {
    }
}
