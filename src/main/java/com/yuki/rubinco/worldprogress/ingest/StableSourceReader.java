package com.yuki.rubinco.worldprogress.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.function.Predicate;

/**
 * Copies a save the game may be writing into memory without locking it. A copy is accepted only
 * when size and modification time are identical before and after reading and the file has been
 * quiet for a while; otherwise it retries and finally gives up.
 */
public final class StableSourceReader {
  private StableSourceReader() { }

  public record Options(long maxBytes, int attempts, Duration retryDelay, Duration quietPeriod) { }

  /** Exactly one of bytes, unchanged or failureCode is meaningful. */
  public record Result(byte[] bytes, BasicFileAttributes attributes, boolean unchanged,
      String failureCode, String failureMessage) {

    public boolean ok() {
      return bytes != null;
    }

    public Long size() {
      return attributes == null ? null : attributes.size();
    }

    public OffsetDateTime modifiedAt() {
      return attributes == null ? null : time(attributes);
    }
  }

  /**
   * @param skipIfUnchanged checked once before the first read; true returns {@code unchanged}
   *     without reading, for scheduled runs whose file metadata matches the last success.
   */
  public static Result read(Path file, Options options, Predicate<BasicFileAttributes> skipIfUnchanged)
      throws IOException, InterruptedException {
    BasicFileAttributes before = null;
    for (int attempt = 1; attempt <= options.attempts(); attempt++) {
      if (attempt > 1) Thread.sleep(options.retryDelay().toMillis());
      before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile()) {
        return failure(before, "SOURCE_INVALID", "保存ファイルが通常ファイルではありません。");
      }
      if (before.size() > options.maxBytes()) {
        return failure(before, "SOURCE_TOO_LARGE", "保存ファイルがサイズ上限を超えています。");
      }
      if (attempt == 1 && skipIfUnchanged.test(before)) {
        return new Result(null, before, true, null, null);
      }
      if (time(before).isAfter(OffsetDateTime.now().minus(options.quietPeriod()))) continue;
      byte[] copy = copy(file, options.maxBytes());
      BasicFileAttributes after =
          Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (copy.length == before.size() && after.size() == before.size()
          && after.lastModifiedTime().equals(before.lastModifiedTime())) {
        return new Result(copy, before, false, null, null);
      }
    }
    return failure(before, "SOURCE_CHANGING",
        "保存処理と重なった可能性があるため、今回の取り込みを見送りました。");
  }

  /** Millisecond precision survives every supported database column type. */
  public static OffsetDateTime time(BasicFileAttributes attributes) {
    return attributes.lastModifiedTime().toInstant().truncatedTo(ChronoUnit.MILLIS)
        .atOffset(ZoneOffset.UTC);
  }

  private static Result failure(BasicFileAttributes attributes, String code, String message) {
    return new Result(null, attributes, false, code, message);
  }

  private static byte[] copy(Path file, long maxBytes) throws IOException {
    try (InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = stream.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
      if (bytes.length > maxBytes) throw new IOException("Source grew past its limit");
      return bytes;
    }
  }
}
