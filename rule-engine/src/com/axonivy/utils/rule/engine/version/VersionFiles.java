package com.axonivy.utils.rule.engine.version;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.axonivy.utils.rule.engine.client.internal.JsonConverter;

import ch.ivyteam.ivy.scripting.objects.File;

/**
 * <pre>
 * rule-engine/discount/versions.json
 * rule-engine/discount/1.dmn
 * rule-engine/discount/2.dmn
 * </pre>
 */
final class VersionFiles {

  static final String ROOT = "rule-engine";
  static final String INDEX = "versions.json";
  static final String DMN_EXTENSION = ".dmn";
  private static final String TEMPORARY = ".tmp";

  /** The last read of each versions.json, read again once the file changed. */
  private static final Map<Path, Cached> READS = new ConcurrentHashMap<>();

  private record Cached(FileTime modified, long size, ModelIndex index) {}

  private final Path root;

  VersionFiles(Path root) {
    this.root = root;
  }

  /** The folder in the file area of the current application, where IvyScript files are stored too. */
  static VersionFiles ofApplication() {
    try {
      return new VersionFiles(new File(ROOT, false).getJavaFile().toPath().toAbsolutePath());
    } catch (IOException ex) {
      throw new UncheckedIOException("Cannot find the file area of the application", ex);
    }
  }

  Path root() {
    return root;
  }

  List<String> models() {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> folders = Files.list(root)) {
      return folders.filter(folder -> Files.isRegularFile(folder.resolve(INDEX)))
          .map(folder -> folder.getFileName().toString())
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .toList();
    } catch (IOException ex) {
      throw new UncheckedIOException("Cannot list the models in " + root, ex);
    }
  }

  Optional<ModelIndex> read(String model) {
    Path index = folder(model).resolve(INDEX);
    try {
      var modified = Files.getLastModifiedTime(index);
      long size = Files.size(index);
      var cached = READS.get(index);
      if (cached == null || !cached.modified().equals(modified) || cached.size() != size) {
        var read = JsonConverter.fromJson(Files.readString(index, StandardCharsets.UTF_8), ModelIndex.class);
        cached = new Cached(modified, size, read);
        READS.put(index, cached);
      }
      return Optional.of(cached.index().copy());
    } catch (NoSuchFileException ex) {
      READS.remove(index);
      return Optional.empty();
    } catch (IOException ex) {
      throw new UncheckedIOException("Cannot read " + index, ex);
    }
  }

  /** Replaces versions.json in one step: a reader sees the old or the new file, never half of it. */
  void write(String model, ModelIndex index) {
    Path target = folder(model).resolve(INDEX);
    String json = JsonConverter.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(index);
    try {
      Files.createDirectories(target.getParent());
      Path temporary = target.resolveSibling(INDEX + TEMPORARY);
      Files.writeString(temporary, json, StandardCharsets.UTF_8);
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ex) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
      READS.remove(target);
    } catch (IOException ex) {
      throw new UncheckedIOException("Cannot write " + target, ex);
    }
  }

  String content(String model, int version) {
    Path file = dmn(model, version);
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("Cannot read " + file, ex);
    }
  }

  /** Written before versions.json names the version: a version that is listed always has its file. */
  void writeContent(String model, int version, String content) {
    Path file = dmn(model, version);
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("Cannot write " + file, ex);
    }
  }

  void delete(String model) {
    Path folder = folder(model);
    if (!Files.exists(folder)) {
      return;
    }
    try (Stream<Path> files = Files.walk(folder)) {
      var all = new ArrayList<>(files.sorted(Comparator.reverseOrder()).toList());
      for (Path file : all) {
        Files.delete(file);
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("Cannot delete " + folder, ex);
    }
    READS.remove(folder.resolve(INDEX));
  }

  private Path folder(String model) {
    return root.resolve(model);
  }

  private Path dmn(String model, int version) {
    return folder(model).resolve(version + DMN_EXTENSION);
  }
}
