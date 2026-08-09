package com.yuki.sevendays_states.service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Loads expandable, original timeline copy parts from the application resource XML. */
@Component
public class TimelineCopyCatalog {

  private static final String RESOURCE_PATH = "timeline-copy.xml";

  private final Map<String, List<String>> parts = load();

  public List<String> part(String id) {
    List<String> lines = parts.get(id);
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("Unknown or empty timeline copy part: " + id);
    }
    return lines;
  }

  private Map<String, List<String>> load() {
    try (InputStream input = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      NodeList partNodes = factory.newDocumentBuilder().parse(input).getElementsByTagName("part");
      Map<String, List<String>> loaded = new LinkedHashMap<>();
      for (int index = 0; index < partNodes.getLength(); index++) {
        Element part = (Element) partNodes.item(index);
        String id = part.getAttribute("id").trim();
        if (id.isEmpty() || loaded.containsKey(id)) {
          throw new IllegalStateException("Timeline copy XML has an invalid part id: " + id);
        }
        List<String> lines = new ArrayList<>();
        NodeList lineNodes = part.getElementsByTagName("line");
        for (int lineIndex = 0; lineIndex < lineNodes.getLength(); lineIndex++) {
          Element line = (Element) lineNodes.item(lineIndex);
          String text = line.getTextContent().trim();
          int weight = line.hasAttribute("weight")
              ? Integer.parseInt(line.getAttribute("weight"))
              : 1;
          if (weight < 1 || (text.isEmpty() && !line.hasAttribute("weight"))) {
            throw new IllegalStateException("Timeline copy XML has an invalid line in: " + id);
          }
          for (int repeat = 0; repeat < weight; repeat++) {
            lines.add(text);
          }
        }
        loaded.put(id, List.copyOf(lines));
      }
      return Map.copyOf(loaded);
    } catch (Exception exception) {
      throw new IllegalStateException("Timeline copy catalog cannot be loaded: " + RESOURCE_PATH, exception);
    }
  }
}
