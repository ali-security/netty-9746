/*
 * Copyright 2015 The Netty Project
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

import java.util.BitSet;

/**
 * Backport of the RFC 6265 cookie character validation that was introduced by
 * netty/netty@d98b21b (PR #4421 "Validate cookie name and value characters").
 *
 * The original commit adds a brand-new {@code io.netty.handler.codec.http.cookie}
 * package; this minimal backport keeps the existing legacy cookie classes and
 * only ports the {@code BitSet}-based octet validation, which is the actual
 * security fix.
 */
final class CookieUtil {

    private static final BitSet VALID_COOKIE_VALUE_OCTETS = validCookieValueOctets();

    private static final BitSet VALID_COOKIE_NAME_OCTETS = validCookieNameOctets(VALID_COOKIE_VALUE_OCTETS);

    // cookie-octet = %x21 / %x23-2B / %x2D-3A / %x3C-5B / %x5D-7E
    // i.e. US-ASCII characters excluding CTLs, whitespace, DQUOTE, comma, semicolon, and backslash.
    private static BitSet validCookieValueOctets() {
        BitSet bits = new BitSet();
        for (int i = 35; i < 127; i++) {
            bits.set(i);
        }
        bits.set('"', false);
        bits.set(',', false);
        bits.set(';', false);
        bits.set('\\', false);
        return bits;
    }

    // token         = 1*<any CHAR except CTLs or separators>
    // separators    = "(" | ")" | "<" | ">" | "@"
    //               | "," | ";" | ":" | "\" | <">
    //               | "/" | "[" | "]" | "?" | "="
    //               | "{" | "}" | SP | HT
    private static BitSet validCookieNameOctets(BitSet validCookieValueOctets) {
        BitSet bits = new BitSet();
        bits.or(validCookieValueOctets);
        bits.set('(', false);
        bits.set(')', false);
        bits.set('<', false);
        bits.set('>', false);
        bits.set('@', false);
        bits.set(':', false);
        bits.set('/', false);
        bits.set('[', false);
        bits.set(']', false);
        bits.set('?', false);
        bits.set('=', false);
        bits.set('{', false);
        bits.set('}', false);
        bits.set(' ', false);
        bits.set('\t', false);
        return bits;
    }

    static int firstInvalidCookieNameOctet(CharSequence cs) {
        return firstInvalidOctet(cs, VALID_COOKIE_NAME_OCTETS);
    }

    static int firstInvalidCookieValueOctet(CharSequence cs) {
        return firstInvalidOctet(cs, VALID_COOKIE_VALUE_OCTETS);
    }

    private static int firstInvalidOctet(CharSequence cs, BitSet bits) {
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            if (!bits.get(c)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Strips wrapping double quotes from a cookie value, returning {@code null}
     * if the leading quote is unbalanced (i.e. there is a leading {@code '"'}
     * but no matching trailing one). This mirrors the helper in the upstream
     * {@code io.netty.handler.codec.http.cookie.CookieUtil}.
     */
    static CharSequence unwrapValue(CharSequence cs) {
        final int len = cs.length();
        if (len > 0 && cs.charAt(0) == '"') {
            if (len >= 2 && cs.charAt(len - 1) == '"') {
                return len == 2 ? "" : cs.subSequence(1, len - 1);
            }
            return null;
        }
        return cs;
    }

    private CookieUtil() {
        // Unused.
    }
}
