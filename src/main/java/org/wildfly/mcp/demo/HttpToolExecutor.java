package org.wildfly.mcp.demo;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class HttpToolExecutor {

    private static final HttpToolExecutor INSTANCE = new HttpToolExecutor();

    private final Map<String, ToolDefinition> tools = new HashMap<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String baseUrl;

    private HttpToolExecutor() {
        loadDefinitions();
    }

    public static HttpToolExecutor getInstance() {
        return INSTANCE;
    }

    private void loadDefinitions() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("mcp-tools.xml")) {
            if (is == null) {
                throw new RuntimeException("mcp-tools.xml not found on classpath");
            }
            DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = db.parse(is);
            Element root = doc.getDocumentElement();
            baseUrl = root.getAttribute("base-url");

            NodeList toolNodes = root.getElementsByTagName("tool");
            for (int i = 0; i < toolNodes.getLength(); i++) {
                Element toolEl = (Element) toolNodes.item(i);
                String name = toolEl.getAttribute("name");

                Element httpEl = (Element) toolEl.getElementsByTagName("http").item(0);
                String method = httpEl.getAttribute("method");
                String path = httpEl.getAttribute("path");
                String contentType = httpEl.hasAttribute("content-type") ? httpEl.getAttribute("content-type") : null;

                String body = null;
                NodeList bodyNodes = httpEl.getElementsByTagName("body");
                if (bodyNodes.getLength() > 0) {
                    body = bodyNodes.item(0).getTextContent();
                }

                tools.put(name, new ToolDefinition(method, path, contentType, body));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load mcp-tools.xml", e);
        }
    }

    public String execute(String toolName, Map<String, String> args) {
        ToolDefinition def = tools.get(toolName);
        if (def == null) {
            return "Unknown tool: " + toolName;
        }

        String path = substitute(def.path, args);
        String url = baseUrl + path;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create(url));

        if (def.contentType != null) {
            requestBuilder.header("Content-Type", def.contentType);
        }

        String body = def.body != null ? substitute(def.body, args) : null;

        switch (def.method) {
            case "GET":
                requestBuilder.GET();
                break;
            case "POST":
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
                break;
            case "PATCH":
                requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(body));
                break;
            case "DELETE":
                requestBuilder.DELETE();
                break;
            default:
                return "Unsupported HTTP method: " + def.method;
        }

        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "HTTP request failed: " + e.getMessage();
        }
    }

    private static String substitute(String template, Map<String, String> args) {
        String result = template;
        for (Map.Entry<String, String> entry : args.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private record ToolDefinition(String method, String path, String contentType, String body) {}
}
