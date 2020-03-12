/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Yegor Bugayenko
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
package com.artipie.goproxy;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Remaining;
import com.artipie.asto.Storage;
import com.artipie.asto.fs.RxFile;
import com.artipie.asto.rx.RxStorageWrapper;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.vertx.reactivex.core.Vertx;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.cactoos.list.Joined;
import org.cactoos.list.ListOf;

/**
 * The Go front.
 *
 * First, you make an instance of this class, providing
 * your storage as an argument:
 *
 * <pre> Goproxy goproxy = new Goproxy(storage);</pre>
 *
 * Then, you put your Go sources to the storage and call
 * {@link Goproxy#update(String,String)}. This method will update all the
 * necessary meta-data files. Right after this, your clients will be able to use
 * the sources, via {@code go get}:
 *
 * <pre> goproxy.update("example.com/foo/bar", "0.0.1").blockingAwait();</pre>
 *
 * You can also do the same in an async way:
 *
 * <pre> goproxy.update("example.com/foo/bar", "0.0.1").subscribe();</pre>
 *
 * That's it.
 *
 * @since 0.1
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 * @checkstyle ReturnCountCheck (500 lines)
 */
public final class Goproxy {

    /**
     * The storage.
     */
    private final Storage storage;

    /**
     * The vertx instance.
     */
    private final Vertx vertx;

    /**
     * Ctor.
     * @param stg The storage
     * @param vertx The Vertx instance
     */
    public Goproxy(final Storage stg, final Vertx vertx) {
        this.storage = stg;
        this.vertx = vertx;
    }

    /**
     * Update the meta info by this artifact.
     *
     * @param repo The name of the repo just updated, e.g. "example.com/foo/bar"
     * @param version The version of the repo, e.g. "0.0.1"
     * @return Completion or error signal.
     */
    public Completable update(final String repo, final String version) {
        return Completable.defer(() -> this.actualUpdate(repo, version));
    }

    /**
     * Update the meta info by this artifact.
     *
     * @param repo The name of the repo just updated, e.g. "example.com/foo/bar"
     * @param version The version of the repo, e.g. "0.0.1"
     * @return Completion or error signal.
     * @throws IOException if fails.
     */
    private Completable actualUpdate(final String repo, final String version) throws IOException {
        final String[] parts = repo.split("/", 2);
        final String lkey = String.format("%s/@v/list", repo);
        return this.loadGoModFile(parts)
            .flatMapCompletable(
                content -> this.saveModWithVersion(repo, version, content)
            ).andThen(
                this.archive(
                    String.format("%s/", parts[1]),
                    String.format("%s@v%s", repo, version)
                )
            ).flatMapCompletable(
                zip -> new RxStorageWrapper(this.storage).save(
                    new Key.From(String.format("%s/@v/v%s.zip", repo, version)),
                    new Content.From(new RxFile(zip, this.vertx.fileSystem()).flow())
                ).andThen(Completable.fromAction(() -> Files.delete(zip)))
            ).andThen(generateVersionJson(version))
            .flatMapCompletable(
                content -> new RxStorageWrapper(this.storage).save(
                    new Key.From(String.format("%s/@v/v%s.info", repo, version)),
                    content
                )
            ).andThen(
                new RxStorageWrapper(this.storage).exists(new Key.From(lkey))
            ).flatMap(
                exists -> {
                    if (exists) {
                        return new RxStorageWrapper(this.storage).value(new Key.From(lkey));
                    } else {
                        return Single.just(new Content.From(new byte[0]));
                    }
                })
            .flatMap(
                content -> updateFileList(version, content)
            ).flatMapCompletable(
                content -> new RxStorageWrapper(this.storage).save(
                    new Key.From(lkey),
                    content
                )
            );
    }

    /**
     * Load mod.go file from the storage.
     *
     * @param parts Parts of the repo path
     * @return Content of the to go.mod file.
     */
    private Single<Content> loadGoModFile(final String... parts) {
        return new RxStorageWrapper(this.storage).value(
            new Key.From(String.format("%s/go.mod", parts[1]))
        );
    }

    /**
     * Save given mod file to the storage.
     *
     * @param repo The name of the repo just updated, e.g. "example.com/foo/bar"
     * @param version The version of the repo, e.g. "0.0.1"
     * @param content The content of to the mod file
     * @return Completion or error signal.
     */
    private Completable saveModWithVersion(final String repo, final String version,
        final Content content) {
        return new RxStorageWrapper(this.storage).save(
            new Key.From(String.format("%s/@v/v%s.mod", repo, version)),
            content
        );
    }

    /**
     * Update files list with the new version.
     *
     * @param version The version of the repo, e.g. "0.0.1"
     * @param content Initial content of files list
     * @return Updated content of files list.
     */
    private static Single<Content> updateFileList(final String version, final Content content) {
        return Flowable.fromPublisher(content)
            .collectInto(
                ByteBuffer.allocate(0),
                (left, right) -> ByteBuffer.allocate(left.remaining() + right.remaining())
                    .put(left).put(right)
                    .flip()
            ).map(
                buf -> new Remaining(buf).bytes()
            ).map(
                buf -> new org.cactoos.text.Joined(
                "\n",
                new Joined<String>(
                    new ListOf<>(
                        new String(buf).split("\n")
                    ),
                    new ListOf<>(String.format("v%s", version))
                )
                ).asString().getBytes()
            ).map(Content.From::new);
    }

    /**
     * Generate a json file with provided version.
     *
     * @param version The version of the repo, e.g. "0.0.1"
     * @return Content of the version json file
     */
    private static Single<Content> generateVersionJson(final String version) {
        return Single.just(
            new Content.From(
                String.format(
                    "{\"Version\":\"v%s\",\"Time\":\"2019-06-28T10:22:31Z\"}",
                    version
                ).getBytes()
            )
        );
    }

    /**
     * Make ZIP archive.
     * @param prefix The prefix
     * @param target The path in the ZIP archive to place files to
     * @return Path to ZIP archive
     * @throws IOException if an error occurred when temporary ZIP file created
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private Single<Path> archive(final String prefix, final String target) throws IOException {
        final Path zip = Files.createTempFile("", ".zip");
        return new RxStorageWrapper(this.storage).list(new Key.From(prefix))
            .flatMapCompletable(
                keys -> {
                    final ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip));
                    return Flowable.fromIterable(keys)
                        .flatMapCompletable(
                            key -> {
                                final String path = String.format(
                                    "%s/%s",
                                    target,
                                    key.string().substring(prefix.length())
                                );
                                final ZipEntry entry = new ZipEntry(path);
                                out.putNextEntry(entry);
                                return new RxStorageWrapper(this.storage).value(key)
                                    .flatMapPublisher(
                                        content -> content
                                    ).flatMapCompletable(
                                        buffer -> {
                                            final byte[] content = new byte[buffer.remaining()];
                                            buffer.get(content);
                                            out.write(content);
                                            return Completable.complete();
                                        }
                                    ).doOnTerminate(out::closeEntry);
                            }, false, 1
                        )
                            .doOnTerminate(out::close);
                }).andThen(Single.just(zip));
    }

}
