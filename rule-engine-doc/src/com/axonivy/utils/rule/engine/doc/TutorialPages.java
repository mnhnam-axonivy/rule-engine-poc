package com.axonivy.utils.rule.engine.doc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ch.ivyteam.ivy.cm.ContentObject;
import ch.ivyteam.ivy.environment.Ivy;

public final class TutorialPages {

  /** Must match the pages that Tutorial.xhtml renders. */
  public static final List<String> PAGE_IDS = List.of("01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11");
  private static final String CMS_FOLDER = "/Tutorial/guide-";

  private TutorialPages() {}

  public static Map<String, String> load() {
    var pages = new LinkedHashMap<String, String>();
    for (String id : PAGE_IDS) {
      pages.put(id, readMarkdown(CMS_FOLDER + id));
    }
    return pages;
  }

  private static String readMarkdown(String cmsPath) {
    Optional<ContentObject> object = Ivy.cm().findObject(cmsPath);
    if (!object.map(ContentObject::exists).orElse(false) || object.get().values().isEmpty()) {
      Ivy.log().warn("Tutorial page not found in the CMS: " + cmsPath);
      return "";
    }
    try (InputStream in = object.get().values().getFirst().read().inputStream()) {
      return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception ex) {
      Ivy.log().error("Could not read the tutorial page " + cmsPath, ex);
      return "";
    }
  }
}
