# Plan: Move Declarative Tool Definitions into WildFly's MCP Subsystem

## Goal
Eliminate the need for `TodoTools.java` and `HttpToolExecutor.java` entirely. The application deploys only `mcp-tools.xml`, and WildFly's MCP subsystem handles tool registration and HTTP execution at deployment time.

## Current State
- App includes `mcp-tools.xml`, `HttpToolExecutor.java`, and `TodoTools.java`
- `TodoTools.java` still required because WildFly MCP only discovers tools via `@Tool` annotations on classes in the deployment
- HTTP logic lives in app code (`HttpToolExecutor`)

## How It Works Today

The current registration flow relies on `@Tool` annotations:

```
@Tool annotation on Java method
  → MCP subsystem scans deployment classes at deploy time
  → discovers annotated methods, registers them in tool registry
  → on tool invocation, reflectively calls the Java method
```

This means every MCP tool requires a Java class in the deployment, even when the tool simply proxies an HTTP call.

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

## Files Changed (in wildfly-ai-feature-pack)

| Area | Change |
|------|--------|
| MCP subsystem | Add `McpToolsXmlParserProcessor` deployment unit processor |
| MCP subsystem | Extend tool registry to accept programmatic (non-annotation) registrations |
| MCP subsystem | Add built-in HTTP tool executor service |
| MCP subsystem | Register the processor in the subsystem's `DeploymentUnitPhaseBuilder` |
| XSD | Define `mcp-tools.xsd` schema for validation |

## Files Changed (in this app)

| File | Change |
|------|--------|
| `mcp-tools.xml` | Move to `WEB-INF/classes/mcp-tools.xml` or `META-INF/mcp-tools.xml` |
| `TodoTools.java` | **Delete** |
| `HttpToolExecutor.java` | **Delete** |
| `pom.xml` | Remove `wildfly-mcp-api` dependency |

## Key Decision Point
The main question is whether the MCP subsystem should support **only** XML-defined HTTP tools, or a more general "programmatic tool registration" API. The HTTP-specific approach is simpler and covers the REST proxy use case well. A general API would be more flexible but adds more surface area to the subsystem.

Suggestion: start with the HTTP-specific approach since it matches the concrete use case, and generalize later if needed.
