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

import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;
import io.netty.util.internal.StringUtil;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decodes an HTTP header value into {@link Cookie}s.  This decoder can decode
 * the HTTP cookie version 0, 1, and 2.
 *
 * <pre>
 * {@link HttpRequest} req = ...;
 * String value = req.getHeader("Cookie");
 * Set&lt;{@link Cookie}&gt; cookies = new {@link CookieDecoder}().decode(value);
 * </pre>
 *
 * @see ClientCookieEncoder
 * @see ServerCookieEncoder
 *
 * @apiviz.stereotype utility
 * @apiviz.has        io.netty.handler.codec.http.Cookie oneway - - decodes
 */
public final class CookieDecoder {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(CookieDecoder.class);

    private static final CookieDecoder STRICT = new CookieDecoder(true);

    private static final CookieDecoder LAX = new CookieDecoder(false);

    private static final char COMMA = ',';

    private final boolean strict;

    /**
     * Decodes the specified HTTP header value into {@link Cookie}s, dropping
     * cookies whose name or value contain characters that are forbidden by
     * RFC 6265.
     *
     * @return the decoded {@link Cookie}s
     */
    public static Set<Cookie> decode(String header) {
        return decode(header, true);
    }

    /**
     * Decodes the specified HTTP header value into {@link Cookie}s.
     *
     * <p>When {@code strict} is {@code true} (the default), this method
     * silently discards any cookie whose name or value contains characters that
     * are forbidden by RFC 6265 - this is the behaviour added by netty/netty
     * @d98b21b to prevent attackers from smuggling additional cookies or
     * cookie attributes (e.g. {@code HttpOnly}) through invalid octets.
     *
     * <p>When {@code strict} is {@code false} the legacy lenient behaviour is
     * preserved for callers that need to interoperate with non-conformant
     * real-world cookies.
     *
     * @return the decoded {@link Cookie}s
     */
    public static Set<Cookie> decode(String header, boolean strict) {
        return (strict ? STRICT : LAX).doDecode(header);
    }

    private CookieDecoder(boolean strict) {
        this.strict = strict;
    }

    private Set<Cookie> doDecode(String header) {
        List<String> names = new ArrayList<String>(8);
        List<String> values = new ArrayList<String>(8);
        extractKeyValuePairs(header, names, values);

        if (names.isEmpty()) {
            return Collections.emptySet();
        }

        int i;
        int version = 0;

        // $Version is the only attribute that can appear before the actual
        // cookie name-value pair.
        if (names.get(0).equalsIgnoreCase(CookieHeaderNames.VERSION)) {
            try {
                version = Integer.parseInt(values.get(0));
            } catch (NumberFormatException e) {
                // Ignore.
            }
            i = 1;
        } else {
            i = 0;
        }

        if (names.size() <= i) {
            // There's a version attribute, but nothing more.
            return Collections.emptySet();
        }

        Set<Cookie> cookies = new TreeSet<Cookie>();
        for (; i < names.size(); i ++) {
            String name = names.get(i);
            String value = values.get(i);

            // Backport of netty/netty@d98b21b: silently discard cookies whose
            // name or value contain characters that are forbidden by RFC 6265
            // (only when strict validation is enabled). This prevents an
            // attacker from smuggling additional cookies or cookie attributes
            // (e.g. HttpOnly) through invalid octets.
            Cookie c = initCookie(name, value);

            boolean discard = false;
            boolean secure = false;
            boolean httpOnly = false;
            String comment = null;
            String commentURL = null;
            String domain = null;
            String path = null;
            long maxAge = Long.MIN_VALUE;
            List<Integer> ports = new ArrayList<Integer>(2);

            for (int j = i + 1; j < names.size(); j++, i++) {
                name = names.get(j);
                value = values.get(j);

                if (CookieHeaderNames.DISCARD.equalsIgnoreCase(name)) {
                    discard = true;
                } else if (CookieHeaderNames.SECURE.equalsIgnoreCase(name)) {
                    secure = true;
                } else if (CookieHeaderNames.HTTPONLY.equalsIgnoreCase(name)) {
                   httpOnly = true;
                } else if (CookieHeaderNames.COMMENT.equalsIgnoreCase(name)) {
                    comment = value;
                } else if (CookieHeaderNames.COMMENTURL.equalsIgnoreCase(name)) {
                    commentURL = value;
                } else if (CookieHeaderNames.DOMAIN.equalsIgnoreCase(name)) {
                    domain = value;
                } else if (CookieHeaderNames.PATH.equalsIgnoreCase(name)) {
                    path = value;
                } else if (CookieHeaderNames.EXPIRES.equalsIgnoreCase(name)) {
                    try {
                        long maxAgeMillis =
                            new HttpHeaderDateFormat().parse(value).getTime() -
                            System.currentTimeMillis();

                        maxAge = maxAgeMillis / 1000 + (maxAgeMillis % 1000 != 0? 1 : 0);
                    } catch (ParseException e) {
                        // Ignore.
                    }
                } else if (CookieHeaderNames.MAX_AGE.equalsIgnoreCase(name)) {
                    maxAge = Integer.parseInt(value);
                } else if (CookieHeaderNames.VERSION.equalsIgnoreCase(name)) {
                    version = Integer.parseInt(value);
                } else if (CookieHeaderNames.PORT.equalsIgnoreCase(name)) {
                    String[] portList = StringUtil.split(value, COMMA);
                    for (String s1: portList) {
                        try {
                            ports.add(Integer.valueOf(s1));
                        } catch (NumberFormatException e) {
                            // Ignore.
                        }
                    }
                } else {
                    break;
                }
            }

            if (c == null) {
                // Mirror upstream behaviour: stop decoding the rest of the
                // header as soon as we see an invalid cookie. This prevents
                // attackers from following an invalid pair with cookies they
                // would otherwise want the application to consume.
                break;
            }

            c.setVersion(version);
            c.setMaxAge(maxAge);
            c.setPath(path);
            c.setDomain(domain);
            c.setSecure(secure);
            c.setHttpOnly(httpOnly);
            if (version > 0) {
                c.setComment(comment);
            }
            if (version > 1) {
                c.setCommentUrl(commentURL);
                c.setPorts(ports);
                c.setDiscard(discard);
            }

            cookies.add(c);
        }

        return cookies;
    }

    private static void extractKeyValuePairs(
            final String header, final List<String> names, final List<String> values) {

        final int headerLen  = header.length();
        loop: for (int i = 0;;) {

            // Skip spaces and separators.
            for (;;) {
                if (i == headerLen) {
                    break loop;
                }
                switch (header.charAt(i)) {
                case '\t': case '\n': case 0x0b: case '\f': case '\r':
                case ' ':  case ',':  case ';':
                    i ++;
                    continue;
                }
                break;
            }

            // Skip '$'.
            for (;;) {
                if (i == headerLen) {
                    break loop;
                }
                if (header.charAt(i) == '$') {
                    i ++;
                    continue;
                }
                break;
            }

            String name;
            String value;

            if (i == headerLen) {
                name = null;
                value = null;
            } else {
                int newNameStart = i;
                keyValLoop: for (;;) {
                    switch (header.charAt(i)) {
                    case ';':
                        // NAME; (no value till ';')
                        name = header.substring(newNameStart, i);
                        value = null;
                        break keyValLoop;
                    case '=':
                        // NAME=VALUE
                        name = header.substring(newNameStart, i);
                        i ++;
                        if (i == headerLen) {
                            // NAME= (empty value, i.e. nothing after '=')
                            value = "";
                            break keyValLoop;
                        }

                        int newValueStart = i;
                        char c = header.charAt(i);
                        if (c == '"' || c == '\'') {
                            // NAME="VALUE" or NAME='VALUE'
                            StringBuilder newValueBuf = new StringBuilder(header.length() - i);
                            final char q = c;
                            boolean hadBackslash = false;
                            i ++;
                            for (;;) {
                                if (i == headerLen) {
                                    value = newValueBuf.toString();
                                    break keyValLoop;
                                }
                                if (hadBackslash) {
                                    hadBackslash = false;
                                    c = header.charAt(i ++);
                                    switch (c) {
                                    case '\\': case '"': case '\'':
                                        // Escape last backslash.
                                        newValueBuf.setCharAt(newValueBuf.length() - 1, c);
                                        break;
                                    default:
                                        // Do not escape last backslash.
                                        newValueBuf.append(c);
                                    }
                                } else {
                                    c = header.charAt(i ++);
                                    if (c == q) {
                                        value = newValueBuf.toString();
                                        break keyValLoop;
                                    }
                                    newValueBuf.append(c);
                                    if (c == '\\') {
                                        hadBackslash = true;
                                    }
                                }
                            }
                        } else {
                            // NAME=VALUE;
                            int semiPos = header.indexOf(';', i);
                            if (semiPos > 0) {
                                value = header.substring(newValueStart, semiPos);
                                i = semiPos;
                            } else {
                                value = header.substring(newValueStart);
                                i = headerLen;
                            }
                        }
                        break keyValLoop;
                    default:
                        i ++;
                    }

                    if (i == headerLen) {
                        // NAME (no value till the end of string)
                        name = header.substring(newNameStart);
                        value = null;
                        break;
                    }
                }
            }

            names.add(name);
            values.add(value);
        }
    }

    /**
     * Returns a {@link DefaultCookie} for the given name/value pair, or
     * {@code null} if the pair must be discarded. Backport of the strict
     * validation logic introduced by netty/netty@d98b21b.
     *
     * <p>When {@link #strict} is {@code true}, cookies whose name or value
     * contain characters forbidden by RFC 6265 are dropped. The
     * {@link DefaultCookie} constructor itself is also wrapped so that its
     * pre-existing rejections (empty name, name starting with {@code '$'},
     * etc.) result in a silent skip rather than an exception bubbling out of
     * {@link #decode(String)} - this matches upstream's behaviour where
     * {@code initCookie} returns {@code null} for any unusable input.
     */
    private Cookie initCookie(String name, String value) {
        if (name == null || name.length() == 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("Skipping cookie with null name");
            }
            return null;
        }

        if (value == null) {
            // Match upstream: a missing value means we cannot construct a
            // cookie at all. The caller will stop decoding the header.
            if (logger.isDebugEnabled()) {
                logger.debug("Skipping cookie with null value");
            }
            return null;
        }

        CharSequence unwrappedValue = CookieUtil.unwrapValue(value);
        if (unwrappedValue == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Skipping cookie because value '" + value +
                        "' has unbalanced wrapping quotes");
            }
            return null;
        }

        int invalidOctetPos;
        if (strict && (invalidOctetPos = CookieUtil.firstInvalidCookieNameOctet(name)) >= 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("Skipping cookie because name '" + name +
                        "' contains invalid char '" + name.charAt(invalidOctetPos) + '\'');
            }
            return null;
        }

        if (strict && (invalidOctetPos = CookieUtil.firstInvalidCookieValueOctet(unwrappedValue)) >= 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("Skipping cookie because value '" + unwrappedValue +
                        "' contains invalid char '" + unwrappedValue.charAt(invalidOctetPos) + '\'');
            }
            return null;
        }

        try {
            return new DefaultCookie(name, unwrappedValue.toString());
        } catch (IllegalArgumentException e) {
            // DefaultCookie rejects e.g. names starting with '$' or names that
            // are empty. Treat this the same as an RFC 6265 violation and
            // silently drop the cookie instead of letting the exception
            // propagate to the caller.
            if (logger.isDebugEnabled()) {
                logger.debug("Skipping cookie rejected by DefaultCookie: " + e.getMessage());
            }
            return null;
        }
    }
}
