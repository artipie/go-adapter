/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 artipie.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.artipie.http;

import com.artipie.asto.Storage;
import com.artipie.http.auth.Action;
import com.artipie.http.auth.Authentication;
import com.artipie.http.auth.BasicIdentities;
import com.artipie.http.auth.Identities;
import com.artipie.http.auth.Permission;
import com.artipie.http.auth.Permissions;
import com.artipie.http.auth.SliceAuth;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.rs.RsStatus;
import com.artipie.http.rs.RsWithStatus;
import com.artipie.http.rt.ByMethodsRule;
import com.artipie.http.rt.RtRule;
import com.artipie.http.rt.RtRulePath;
import com.artipie.http.rt.SliceRoute;
import com.artipie.http.slice.LoggingSlice;
import com.artipie.http.slice.SliceDownload;
import com.artipie.http.slice.SliceSimple;
import com.artipie.http.slice.SliceWithHeaders;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.regex.Pattern;
import org.reactivestreams.Publisher;

/**
 * Slice implementation that provides HTTP API (Go module proxy protocol) for Golang repository.
 * @since 0.3
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 */
public final class GoSlice implements Slice {

    /**
     * Text header.
     */
    private static final String TEXT_PLAIN = "text/plain";

    /**
     * Origin.
     */
    private final Slice origin;

    /**
     * Ctor.
     * @param storage Storage
     */
    public GoSlice(final Storage storage) {
        this(storage, Permissions.FREE, Identities.ANONYMOUS);
    }

    /**
     * Ctor.
     * @param storage Storage
     * @param perms Permissions
     * @param auth Authentication
     */
    public GoSlice(final Storage storage, final Permissions perms, final Authentication auth) {
        this(storage, perms, new BasicIdentities(auth));
    }

    /**
     * Ctor.
     * @param storage Storage
     * @param perms Permissions
     * @param users Users
     */
    public GoSlice(final Storage storage, final Permissions perms, final Identities users) {
        this.origin = new SliceRoute(
            GoSlice.pathGet(
                ".+/@v/v.*\\.info",
                GoSlice.createSlice(storage, "application/json", perms, users)
            ),
            GoSlice.pathGet(
                ".+/@v/v.*\\.mod",
                GoSlice.createSlice(storage, GoSlice.TEXT_PLAIN, perms, users)
            ),
            GoSlice.pathGet(
                ".+/@v/v.*\\.zip", GoSlice.createSlice(storage, "application/zip", perms, users)
            ),
            GoSlice.pathGet(
                ".+/@v/list", GoSlice.createSlice(storage, GoSlice.TEXT_PLAIN, perms, users)
            ),
            GoSlice.pathGet(
                ".+/@latest",
                new SliceAuth(
                    new LatestSlice(storage),
                    new Permission.ByName(perms, Action.Standard.READ), users
                )
            ),
            new RtRulePath(
                RtRule.FALLBACK,
                new SliceSimple(
                    new RsWithStatus(RsStatus.NOT_FOUND)
                )
            )
        );
    }

    @Override
    public Response response(
        final String line, final Iterable<Map.Entry<String, String>> headers,
        final Publisher<ByteBuffer> body) {
        return this.origin.response(line, headers, body);
    }

    /**
     * Creates slice instance.
     * @param storage Storage
     * @param type Content-type
     * @param perms Permissions
     * @param users Users
     * @return Slice
     * @checkstyle ParameterNumberCheck (10 lines)
     */
    private static Slice createSlice(final Storage storage, final String type,
        final Permissions perms, final Identities users) {
        return new SliceAuth(
            new SliceWithHeaders(
                new SliceDownload(storage),
                new Headers.From("content-type", type)
            ),
            new Permission.ByName(perms, Action.Standard.READ),
            users
        );
    }

    /**
     * This method simply encapsulates all the RtRule instantiations.
     * @param pattern Route pattern
     * @param slice Slice implementation
     * @return Path route slice
     */
    private static RtRulePath pathGet(final String pattern, final Slice slice) {
        return new RtRulePath(
            new RtRule.All(
                new RtRule.ByPath(Pattern.compile(pattern)),
                new ByMethodsRule(RqMethod.GET)
            ),
            new LoggingSlice(slice)
        );
    }
}
