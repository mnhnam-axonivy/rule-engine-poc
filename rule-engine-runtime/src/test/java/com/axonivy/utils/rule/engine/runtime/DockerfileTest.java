package com.axonivy.utils.rule.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The image is built two ways: from target/docker after {@code mvn package}, with src/main/docker/Dockerfile, and with
 * Maven inside Docker, with the Dockerfile of the project (docker compose). Both have to build the same image.
 */
class DockerfileTest {

  /** Where the stage of the image in the Dockerfile of the project takes the files from: the Maven stage. */
  private static final String FROM_MAVEN_STAGE = "--from=build ";
  private static final String MAVEN_OUTPUT = "/build/target/docker/";

  @Test
  void composeBuildsTheImageOfSrcMainDocker() throws IOException {
    var compose = imageStage(Path.of("Dockerfile")).stream()
        .map(line -> line.replace(FROM_MAVEN_STAGE, "").replace(MAVEN_OUTPUT, ""))
        .toList();
    assertThat(compose).as("the last stage of Dockerfile, with the files of the Maven stage")
        .isEqualTo(imageStage(Path.of("src/main/docker/Dockerfile")));
  }

  /** The instructions from the last FROM on, without comments and blank lines. */
  private static List<String> imageStage(Path dockerfile) throws IOException {
    var lines = Files.readAllLines(dockerfile).stream()
        .map(String::strip)
        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
        .toList();
    int lastFrom = 0;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith("FROM ")) {
        lastFrom = i;
      }
    }
    return lines.subList(lastFrom, lines.size());
  }
}
