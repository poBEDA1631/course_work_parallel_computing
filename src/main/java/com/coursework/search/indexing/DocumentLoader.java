package com.coursework.search.indexing;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One {@code .txt} file per document; the document id is the file name without the extension. */
public final class DocumentLoader {
  public static final int BATCH_SIZE = 64;
  private static final String EXTENSION = ".txt";

  private DocumentLoader() {}

  /** Lists the documents sorted by file name, so doc ids are stable across builds. */
  public static List<Path> listDocuments(Path datasetDir) throws IOException {
    List<Path> files = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(datasetDir, "*" + EXTENSION)) {
      for (Path entry : entries) {
        if (Files.isRegularFile(entry)) {
          files.add(entry);
        }
      }
    }
    files.sort(null);
    return files;
  }

  /** Splits {@code files} into consecutive batches of at most {@link #BATCH_SIZE}. */
  public static List<List<Path>> batches(List<Path> files) {
    List<List<Path>> batches = new ArrayList<>();
    for (int from = 0; from < files.size(); from += BATCH_SIZE) {
      batches.add(files.subList(from, Math.min(from + BATCH_SIZE, files.size())));
    }
    return batches;
  }

  public static String documentId(Path file) {
    String name = file.getFileName().toString();
    return name.endsWith(EXTENSION) ? name.substring(0, name.length() - EXTENSION.length()) : name;
  }
}
