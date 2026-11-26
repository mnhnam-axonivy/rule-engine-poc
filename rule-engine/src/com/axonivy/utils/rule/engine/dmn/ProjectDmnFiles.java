package com.axonivy.utils.rule.engine.dmn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.Ivy;

public final class ProjectDmnFiles {

  public static final String DMN_FOLDER = "dmn";
  public static final String DMN_EXTENSION = ".dmn";
  private static final int MAX_DEPTH = 8;
  private static final long LISTING_TTL_MILLIS = 3000;

  public record DmnFile(String model, Path path, String project, String projectCoordinates) {

    public String relativePath(Path projectDirectory) {
      return projectDirectory.relativize(path).toString().replace('\\', '/');
    }
  }

  public record LoadedDmn(DmnFile file, String content, String hash, DmnModelInfo info, String parseError) {}

  private record Listing(long loadedAt, List<DmnFile> files) {}

  private record CachedContent(long lastModified, long size, LoadedDmn loaded) {}

  private static final Map<Long, Listing> LISTINGS = new ConcurrentHashMap<>();
  private static final Map<Path, CachedContent> CONTENTS = new ConcurrentHashMap<>();
  private static final String PACKED_ROOT = "/META-INF/ivy";
  private static final Map<Path, FileSystem> PACKED = new ConcurrentHashMap<>();

  private ProjectDmnFiles() {}

  /** A model of the project itself hides an equally named model of a required project. */
  public static List<DmnFile> list(Project project) {
    if (project == null) {
      return List.of();
    }
    var cached = LISTINGS.get(project.id());
    if (cached != null && System.currentTimeMillis() - cached.loadedAt() < LISTING_TTL_MILLIS) {
      return cached.files();
    }
    var files = new ArrayList<DmnFile>();
    Stream.concat(Stream.of(project), project.allRequiredProjects())
        .forEach(source -> filesOf(source).stream()
            .filter(file -> files.stream().noneMatch(known -> known.model().equals(file.model())))
            .forEach(files::add));
    var listing = List.copyOf(files);
    LISTINGS.put(project.id(), new Listing(System.currentTimeMillis(), listing));
    return listing;
  }

  public static Optional<DmnFile> find(Project project, String model) {
    return list(project).stream().filter(file -> file.model().equals(model)).findFirst();
  }

  public static void refresh() {
    LISTINGS.clear();
  }

  public static List<DmnFile> filesOf(Project project) {
    Path root = root(project.directory());
    if (root == null) {
      return List.of();
    }
    try (Stream<Path> paths = Files.walk(root, MAX_DEPTH)) {
      return paths
          .filter(path -> path.getFileName() != null && path.getFileName().toString().endsWith(DMN_EXTENSION))
          .filter(path -> !isIgnored(root.relativize(path)))
          .filter(Files::isRegularFile)
          .sorted()
          .map(path -> new DmnFile(modelName(path), path, project.name(), coordinates(project)))
          .toList();
    } catch (IOException | RuntimeException ex) {
      Ivy.log().warn("Cannot list the .dmn files of project " + project.name(), ex);
      return List.of();
    }
  }

  public static LoadedDmn load(DmnFile file) {
    try {
      long lastModified = Files.getLastModifiedTime(file.path()).toMillis();
      long size = Files.size(file.path());
      var cached = CONTENTS.get(file.path());
      if (cached != null && cached.lastModified() == lastModified && cached.size() == size) {
        return cached.loaded();
      }
      String content = Files.readString(file.path(), StandardCharsets.UTF_8);
      DmnModelInfo info = null;
      String parseError = null;
      try {
        info = DmnModelInfo.parse(content);
      } catch (IllegalArgumentException ex) {
        parseError = ex.getMessage();
      }
      var loaded = new LoadedDmn(file, content, hash(content), info, parseError);
      CONTENTS.put(file.path(), new CachedContent(lastModified, size, loaded));
      return loaded;
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot read " + file.path(), ex);
    }
  }

  /**
   * An engine keeps a deployed project packed: {@link Project#directory()} is then its .iar file, a zip with the files
   * of the project under META-INF/ivy. Paths into it can be read like any other; they cannot be written.
   */
  public static Path root(Path directory) {
    if (directory == null) {
      return null;
    }
    if (Files.isDirectory(directory)) {
      return directory;
    }
    if (!Files.isRegularFile(directory)) {
      return null;
    }
    FileSystem packed = PACKED.computeIfAbsent(directory.toAbsolutePath().normalize(), ProjectDmnFiles::open);
    if (packed == null) {
      return null;
    }
    Path files = packed.getPath(PACKED_ROOT);
    return Files.isDirectory(files) ? files : packed.getPath("/");
  }

  private static FileSystem open(Path iar) {
    try {
      // stays open: a deployed version of a project never changes, a new one is a new file
      return FileSystems.newFileSystem(iar);
    } catch (IOException | RuntimeException ex) {
      Ivy.log().warn("Cannot read the packed project " + iar, ex);
      return null;
    }
  }

  public static String modelName(Path path) {
    String fileName = path.getFileName().toString();
    return fileName.substring(0, fileName.length() - DMN_EXTENSION.length());
  }

  /** Build output and hidden folders hold copies, not sources. */
  private static boolean isIgnored(Path relative) {
    for (Path segment : relative) {
      String name = segment.toString();
      if (name.equals("target") || name.equals("classes") || name.equals("node_modules") || name.startsWith(".")) {
        return true;
      }
    }
    return false;
  }

  private static String coordinates(Project project) {
    try {
      var maven = project.mavenCoordinates();
      return maven.id() + ":" + maven.version();
    } catch (RuntimeException ex) {
      return project.name();
    }
  }

  public static String hash(String content) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      String normalized = content.replace("\r\n", "\n").replace('\r', '\n').strip();
      return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
