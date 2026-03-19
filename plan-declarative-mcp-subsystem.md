# Plan: Move Declarative Tool Definitions into WildFly's MCP Subsystem

## Goal
Eliminate the need for `TodoTools.java` and `HttpToolExecutor.java` entirely. The application deploys only `mcp-tools.xml`, and WildFly's MCP subsystem handles tool registration and HTTP execution at deployment time.

## Current State
- App includes `mcp-tools.xml`, `HttpToolExecutor.java`, and `TodoTools.java`
- `TodoTools.java` still required because WildFly MCP only discovers tools via `@Tool` annotations on classes in the deployment
- HTTP logic lives in app code (`HttpToolExecutor`)

## How It Works Today

The current registration flow relies on `@Tool` annotations and involves three deployment phases:

1. **DEPENDENCIES phase** — `MCPServerDependencyProcessor` scans the deployment's Jandex annotation index for `@Tool` methods. For each one, it builds `MCPFeatureMetadata` (containing `MethodMetadata` + `ArgumentMetadata` list) and stores it in a `WildFlyMCPRegistry` attached to the deployment unit.

2. **POST_MODULE phase** — `MCPServerCDIProcessor` registers `MCPPortableExtension` (a CDI extension) with Weld. In its `AfterTypeDiscovery` hook, it loads each tool's declaring class, calls `registry.prepareTool(name, clazz)` which creates a `MethodHandle` for the annotated method and stores it in `toolInvokers`. It also adds `@MCPTool` qualifier and `@Singleton` scope to the bean class.

3. **INSTALL phase** — `MCPServerDeploymentProcessor` installs HTTP handlers (`/mcp/sse`, `/mcp/messages/{id}`) via `MCPSseHandlerServiceInstaller`.

At runtime, `ToolMessageHandler.toolsCall()` looks up the tool metadata, resolves the CDI bean via `@MCPTool` qualifier, prepares arguments from JSON, and invokes via the stored `MethodHandle`.

This means every MCP tool requires a Java class in the deployment with an annotated method, even when the tool simply proxies an HTTP call.

## Proposed Changes

### 1. MCP Subsystem: Deployment Scanner for `mcp-tools.xml`
Add a **deployment unit processor** in the `mcp-server` subsystem that:
- Scans deployments for `META-INF/mcp-tools.xml` (or `WEB-INF/classes/mcp-tools.xml`)
- Parses the XML tool definitions
- Registers them alongside annotation-discovered tools during the deployment phase

This is similar to how WildFly already handles `persistence.xml`, `beans.xml`, or `jboss-web.xml` — a well-known descriptor triggers subsystem behavior.

### 2. MCP Subsystem: Programmatic Tool Registration (replacing `@Tool` annotations)
The key architectural change: the MCP subsystem's tool registry currently only accepts tools discovered via `@Tool` annotation scanning. It needs a **second registration path** — programmatic registration — so that XML-defined tools can be registered without any Java class.

For each `<tool>` element in the XML, the deployment processor:
- Creates an internal tool representation (same data structure used for annotation-based tools)
- Generates the tool's argument schema from `<arg>` elements (name, type, required, description)
- Registers the tool in the MCP server's tool registry with an **HTTP-based invocation handler** instead of a reflective method call

The new registration flow becomes:

```
mcp-tools.xml in deployment
  → MCP subsystem parses XML at deploy time
  → programmatically registers tools in the same registry
  → on tool invocation, executes HTTP call directly (no Java class needed)
```

Both paths (annotation-based and XML-based) coexist — a deployment can use either or both.

### 3. MCP Subsystem: Built-in HTTP Executor
The subsystem provides a built-in HTTP invocation handler for XML-defined tools:
- On tool invocation, performs `${arg}` substitution on path and body templates
- Executes the HTTP call using a managed `HttpClient` (could be pooled/configurable per deployment)
- Returns the response body as the tool result

The `base-url` could be resolved relative to the deployment's own context if it targets itself, or remain absolute for external services.

### 4. XML Schema Enhancements (optional)
Once the subsystem owns the XML, consider enhancements:
- **Response mapping**: `<response status="404">Not found</response>` for status-code-specific messages
- **Base URL from config**: `base-url="${env.TODO_BACKEND_URL}"` with expression resolution via WildFly's expression resolver
- **Authentication**: `<http auth="bearer" token-ref="..."/>` referencing Elytron credentials

### 5. Application Simplification
After this, the todo app would contain:
- `mcp-tools.xml` (tool definitions)
- The REST backend classes (`Todo.java`, `TodoResource.java`, etc.)
- **No** `TodoTools.java`, **no** `HttpToolExecutor.java`, **no** `wildfly-mcp-api` dependency

## Implementation Details

All changes below are in `wildfly-ai-feature-pack/wildfly-mcp/`. File paths are relative to that root.

### Step 1: XML Tool Definition Data Model (injection module)

Create a record to hold parsed XML tool definitions, separate from annotation-based metadata:

**Create `injection/src/main/java/org/wildfly/extension/mcp/injection/tool/HttpToolDefinition.java`**

```java
public record HttpToolDefinition(
    String name,
    String description,
    String method,          // GET, POST, PATCH, DELETE
    String path,            // e.g. "/${id}" — with ${arg} placeholders
    String contentType,     // e.g. "application/json", nullable
    String bodyTemplate,    // e.g. {"title": "${title}"}, nullable
    List<ArgumentMetadata> arguments
) {}
```

### Step 2: XML Parser in the Deployment Processor (subsystem module)

**Create `subsystem/src/main/java/org/wildfly/extension/mcp/deployment/MCPToolsXmlProcessor.java`**

A new `DeploymentUnitProcessor` that runs in the **DEPENDENCIES** phase (same as `MCPServerDependencyProcessor`), or immediately after it:

1. Look for `mcp-tools.xml` in the deployment's resource root (via `DeploymentUnit.getAttachment(Attachments.RESOURCE_ROOTS)` or `VirtualFile`)
2. Parse the XML using `javax.xml.parsers.DocumentBuilder`
3. For each `<tool>` element:
   - Parse `<arg>` elements into `ArgumentMetadata` (reusing the existing record)
   - Parse `<http>` element for method, path, content-type, body template
   - Build an `HttpToolDefinition`
   - Build an `MCPFeatureMetadata` with a synthetic `MethodMetadata`:
     - `declaringClassName` = a sentinel value (e.g. `"__xml_http_tool__"`)
     - `returnType` = `"java.lang.String"`
     - `arguments` = parsed from `<arg>` elements
   - Register in the existing `WildFlyMCPRegistry` via `registry.addTool(name, metadata)`
4. Store the `HttpToolDefinition` map in the registry (new field) for use at invocation time

**Modify `MCPSubsystemRegistrar.java`**

Register the new processor alongside the existing ones. It should run in the same DEPENDENCIES phase:

```java
context.registerDeploymentUnitProcessor(NAME, Phase.DEPENDENCIES, PRIORITY + 1, new MCPToolsXmlProcessor());
```

### Step 3: Extend WildFlyMCPRegistry (injection module)

**Modify `injection/src/main/java/org/wildfly/extension/mcp/injection/WildFlyMCPRegistry.java`**

Add storage and accessors for HTTP tool definitions:

```java
private final Map<String, HttpToolDefinition> httpToolDefinitions = new HashMap<>();

public void addHttpToolDefinition(String name, HttpToolDefinition def) {
    httpToolDefinitions.put(name, def);
}

public HttpToolDefinition getHttpToolDefinition(String name) {
    return httpToolDefinitions.get(name);
}

public boolean isHttpTool(String name) {
    return httpToolDefinitions.containsKey(name);
}
```

### Step 4: Skip CDI/MethodHandle for XML-defined Tools (injection module)

**Modify `injection/src/main/java/org/wildfly/extension/mcp/injection/tool/MCPPortableExtension.java`**

In the `atd()` method, when iterating tools, skip XML-defined tools (those with the sentinel `declaringClassName`). They don't have a Java class, so no CDI bean or `MethodHandle` is needed:

```java
for (Map.Entry<String, MCPFeatureMetadata> entry : registry.getTools().entrySet()) {
    if (registry.isHttpTool(entry.getKey())) {
        continue; // no Java class to prepare
    }
    // existing logic: load class, prepareTool, add qualifiers...
}
```

### Step 5: HTTP Executor in ToolMessageHandler (subsystem module)

**Modify `subsystem/src/main/java/org/wildfly/extension/mcp/server/ToolMessageHandler.java`**

In `toolsCall()`, before the existing CDI bean resolution logic, check if this is an XML-defined HTTP tool:

```java
if (registry.isHttpTool(toolName)) {
    HttpToolDefinition def = registry.getHttpToolDefinition(toolName);
    // Substitute ${arg} placeholders in path and body
    String path = substitute(def.path(), args);
    String url = def.baseUrl() + path;
    String body = def.bodyTemplate() != null ? substitute(def.bodyTemplate(), args) : null;

    // Build and execute HTTP request
    HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().uri(URI.create(url));
    if (def.contentType() != null) {
        reqBuilder.header("Content-Type", def.contentType());
    }
    switch (def.method()) {
        case "GET"    -> reqBuilder.GET();
        case "POST"   -> reqBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
        case "PATCH"  -> reqBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(body));
        case "DELETE" -> reqBuilder.DELETE();
    }
    HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
    // Send response body as text content via responder
    return;
}

// existing CDI + MethodHandle path for annotation-based tools...
```

The `substitute()` method replaces `${argName}` placeholders with values from the args JsonObject.

### Step 6: tools/list Support

**No changes needed** — `ToolMessageHandler.toolsList()` already iterates `registry.getTools()`. Since XML-defined tools are registered via `registry.addTool()` with proper `MCPFeatureMetadata`, they appear in `tools/list` responses automatically with their name, description, and argument schema.

### Summary of Changes

| File (relative to `wildfly-mcp/`) | Action | What |
|---|---|---|
| `injection/.../tool/HttpToolDefinition.java` | **Create** | Data record for XML-defined HTTP tools |
| `injection/.../WildFlyMCPRegistry.java` | **Modify** | Add `httpToolDefinitions` map + accessors |
| `injection/.../tool/MCPPortableExtension.java` | **Modify** | Skip CDI/MethodHandle prep for HTTP tools |
| `subsystem/.../deployment/MCPToolsXmlProcessor.java` | **Create** | XML parser deployment unit processor |
| `subsystem/.../MCPSubsystemRegistrar.java` | **Modify** | Register new processor |
| `subsystem/.../server/ToolMessageHandler.java` | **Modify** | Add HTTP execution path before CDI path |

### Changes in this app (mcp-wildfly)

| File | Action |
|---|---|
| `src/main/resources/mcp-tools.xml` | **Keep** (already exists) |
| `src/main/java/.../TodoTools.java` | **Delete** |
| `src/main/java/.../HttpToolExecutor.java` | **Delete** |
| `pom.xml` | Remove `wildfly-mcp-api` dependency |

## Key Decision Point
The main question is whether the MCP subsystem should support **only** XML-defined HTTP tools, or a more general "programmatic tool registration" API. The HTTP-specific approach is simpler and covers the REST proxy use case well. A general API would be more flexible but adds more surface area to the subsystem.

Suggestion: start with the HTTP-specific approach since it matches the concrete use case, and generalize later if needed.
