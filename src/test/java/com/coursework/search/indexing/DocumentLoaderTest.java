package com.coursework.search.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentLoaderTest {
  @TempDir Path dir;

  @Test
  void listsOnlyRegularTxtFilesSortedByName() throws IOException {
    Files.writeString(dir.resolve("b.txt"), "b");
    Files.writeString(dir.resolve("a.txt"), "a");
    Files.writeString(dir.resolve("notes.md"), "x");
    Files.writeString(dir.resolve(".complete"), "");
    Files.createDirectory(dir.resolve("dir.txt"));
    List<Path> files = DocumentLoader.listDocuments(dir);
    assertEquals(List.of(dir.resolve("a.txt"), dir.resolve("b.txt")), files);
    assertEquals("a", DocumentLoader.documentId(files.get(0)));
  }

  @Test
  void splitsIntoBatchesOfSixtyFour() {
    List<Path> files = new ArrayList<>();
    for (int i = 0; i < 130; i++) {
      files.add(Path.of(i + ".txt"));
    }
    List<List<Path>> batches = DocumentLoader.batches(files);
    assertEquals(3, batches.size());
    assertEquals(64, batches.get(0).size());
    assertEquals(64, batches.get(1).size());
    assertEquals(2, batches.get(2).size());
    assertEquals(Path.of("64.txt"), batches.get(1).get(0));
    assertEquals(List.of(), DocumentLoader.batches(List.of()));
  }
}
