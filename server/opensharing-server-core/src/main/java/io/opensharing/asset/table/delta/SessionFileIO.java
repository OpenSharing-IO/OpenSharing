package io.opensharing.asset.table.delta;

import io.delta.kernel.defaults.engine.fileio.FileIO;
import io.delta.kernel.defaults.engine.fileio.InputFile;
import io.delta.kernel.defaults.engine.fileio.OutputFile;
import io.delta.kernel.defaults.engine.hadoopio.HadoopInputFile;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * Read-only Kernel file access through a file system that one session owns. Hadoop's shared file
 * system cache is keyed by bucket rather than by credentials, so a cached instance would keep
 * serving the first request's credentials to every later one.
 */
final class SessionFileIO implements FileIO, Closeable {

  private final Configuration configuration;
  private final FileSystem fileSystem;

  /** Opens a file system for {@code root} that only this session uses, until {@link #close}. */
  SessionFileIO(Configuration configuration, String root) {
    this.configuration = configuration;
    try {
      this.fileSystem = FileSystem.newInstance(new Path(root).toUri(), configuration);
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    }
  }

  /** The files in the directory of {@code filePath} that sort at or after it, by name. */
  @Override
  public CloseableIterator<FileStatus> listFrom(String filePath) throws IOException {
    Path path = new Path(filePath);
    return Utils.toCloseableIterator(
        Arrays.stream(fileSystem.listStatus(path.getParent()))
            .filter(file -> file.getPath().getName().compareTo(path.getName()) >= 0)
            .sorted(Comparator.comparing(file -> file.getPath().getName()))
            .map(SessionFileIO::status)
            .iterator());
  }

  /** The size and modification time of the file at {@code path}. */
  @Override
  public FileStatus getFileStatus(String path) throws IOException {
    return status(fileSystem.getFileStatus(new Path(path)));
  }

  /** {@code path} qualified with the file system's scheme and authority. */
  @Override
  public String resolvePath(String path) {
    return fileSystem.makeQualified(new Path(path)).toString();
  }

  /**
   * A read handle for the existing file at {@code path}. Kernel opens streams on it as it reads log
   * and checkpoint files; nothing is written.
   */
  @Override
  public InputFile newInputFile(String path, long fileSize) {
    return new HadoopInputFile(fileSystem, new Path(path), fileSize);
  }

  /** Unsupported: shared tables are read-only. */
  @Override
  public boolean mkdirs(String path) {
    throw new UnsupportedOperationException("shared tables are read-only");
  }

  /** Unsupported: shared tables are read-only. */
  @Override
  public OutputFile newOutputFile(String path) {
    throw new UnsupportedOperationException("shared tables are read-only");
  }

  /** Unsupported: shared tables are read-only. */
  @Override
  public boolean delete(String path) {
    throw new UnsupportedOperationException("shared tables are read-only");
  }

  /** The Hadoop setting {@code key} of this session's configuration, if set. */
  @Override
  public Optional<String> getConf(String key) {
    return Optional.ofNullable(configuration.get(key));
  }

  /** Closes the session's file system and its storage connections. */
  @Override
  public void close() {
    try {
      fileSystem.close();
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    }
  }

  /** Kernel's file status for a Hadoop one. */
  private static FileStatus status(org.apache.hadoop.fs.FileStatus file) {
    return FileStatus.of(file.getPath().toString(), file.getLen(), file.getModificationTime());
  }
}
