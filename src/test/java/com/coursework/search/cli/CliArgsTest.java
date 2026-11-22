package com.coursework.search.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CliArgsTest {
  @Test
  void wordsAreJoinedWithSingleSpacesAndDefaultsApply() throws Exception {
    CliArgs args = CliArgs.parse(new String[] {"jazz", "piano"});
    assertEquals(new CliArgs("localhost", 8080, "jazz piano"), args);
    assertEquals("ws://localhost:8080/ws/search", args.url());
  }

  @Test
  void flagsMayAppearAnywhere() throws Exception {
    assertEquals(
        new CliArgs("search", 9090, "good movies about space"),
        CliArgs.parse(
            new String[] {
              "good", "--port", "9090", "movies", "about", "--host", "search", "space"
            }));
  }

  @Test
  void quotedQueryWithInnerSpacesStaysOneQuery() throws Exception {
    assertEquals("sad songs", CliArgs.parse(new String[] {"  sad songs "}).query());
  }

  @Test
  void missingQueryIsAUsageError() {
    assertThrows(CliArgs.UsageException.class, () -> CliArgs.parse(new String[0]));
    assertThrows(
        CliArgs.UsageException.class,
        () -> CliArgs.parse(new String[] {"--host", "x", "--port", "1"}));
    assertThrows(CliArgs.UsageException.class, () -> CliArgs.parse(new String[] {" ", ""}));
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "0", "65536", "-1", "80.5"})
  void invalidPortIsAUsageError(String port) {
    assertThrows(
        CliArgs.UsageException.class, () -> CliArgs.parse(new String[] {"--port", port, "q"}));
  }

  @Test
  void flagWithoutValueOrUnknownFlagIsAUsageError() {
    assertThrows(CliArgs.UsageException.class, () -> CliArgs.parse(new String[] {"q", "--port"}));
    assertThrows(
        CliArgs.UsageException.class, () -> CliArgs.parse(new String[] {"--host", "--port", "1"}));
    assertThrows(CliArgs.UsageException.class, () -> CliArgs.parse(new String[] {"--foo", "q"}));
  }
}
