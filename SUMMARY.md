# MCP WildFly Demo — Project Summary

This project explores how to expose a REST backend as MCP tools using WildFly's MCP subsystem, progressively moving from application-level code to a fully declarative, server-managed approach.

There is a wide spectrum of approaches to expose existing applications as MCP servers (summarized in [Exposing MCP from Legacy Java: Architecture Patterns That Actually Scale](https://www.the-main-thread.com/p/exposing-mcp-legacy-java-jakarta-ee-architecture?publication_id=4194688&utm_campaign=email-post-title&r=av42p&utm_medium=email)).

Most of them are beyond the scope of what is achievable __within__ WildFly.
This experiments focuses on answering the question: **But What If the Legacy App Exposes MCP Directly?**

To play around this question, I'm starting from an existing deployment from the [todo-backend quickstart](https://github.com/wildfly/quickstart/tree/main/todo-backend).
The todo backend exposes a standard REST API at the `/todo-backend/` endpoints with CRUD operations for todo items.

## Stage 0: Modify the todo-backend codebase to use @Tool annotation

The first approach would be to modify the todo-backend and add directly the code to expose it with @Tool annotations.
This requires changes to the existing application and would be similar to the [weather](https://github.com/ehsavoie/wildfly-weather/blob/main/src/main/java/org/acme/Weather.java) example.

### Summary

* (+) The MCP Tool API is not constrained by the existing HTTP API
* (+) It's not bound to the deployment HTTP API (can directly use CDI / EJB)
* (-) It requires changes to the existing code
* (-) It might still have to go through the HTTP layer if there is some validation/business logic in that layer
* (-) It is bound to the existing deployment security constraints (which might not be a good thing if the user want 
  to have different security schemes for AI agents vs software calls)

## Stage 1: @Tool Annotations with HTTP Calls

A WildFly application is configured with the `mcp-server` subsystem layer and deployed alongside the existing `todo-backend.war`.

MCP tools are implemented as Java methods annotated with `@Tool` and `@ToolArg` from the `wildfly-mcp-api`. Each method manually constructs HTTP requests using `java.net.http.HttpClient` to call the todo-backend REST API.

Four tools are exposed: `listTodos`, `addTodo`, `completeTodo`, `deleteTodo`.

**Key files:**
- `src/main/java/org/wildfly/mcp/demo/TodoTools.java` — annotated methods with inline HTTP logic

Commit: `8d14bb1a480dccedd97f4761ea9f6ddf66258f68`

### Summary:

* (+) No modification to the existing todo-backend.war
* (+) The Tool API is not constrained by the HTTP API (it can aggregate requests, transform payload)
* (+) It is another deployment that can have different security schemes from the existing application
* (-) It requires new code with boilerplate code (HTTP, security)

## Stage 2: Declarative XML + Application-side Executor

The hardcoded HTTP logic is extracted into a declarative XML file (`mcp-tools.xml`) that defines each tool's name, description, arguments, HTTP method, URL path, and request body template with `${arg}` placeholders.

A generic `HttpToolExecutor` parses the XML at startup and executes HTTP calls based on the definitions. `TodoTools.java` becomes thin delegates — the `@Tool` annotations remain (required by WildFly MCP for registration) but all HTTP logic is configuration-driven.

**Key files:**
- `src/main/resources/mcp-tools.xml` — declarative tool definitions
- `src/main/java/org/wildfly/mcp/demo/HttpToolExecutor.java` — XML parser + HTTP executor
- `src/main/java/org/wildfly/mcp/demo/TodoTools.java` — simplified delegates

Commit `60361a819dac0e2bdefdd68b910ad883498ed3d4`

### Summary

* (+) No modification to the existing todo-backend.war
* (+) The new code is generic and can apply to any existing deployment
* (+) It is another deployment that can have different security schemes from the existing application
* (+) Templating could simplify the mapping between the MCP I/O and the HTTP I/O (to some extent)
* (-) The Tool API is still constrained by the HTTP API (there is a 1:1 mapping between MCP Tool and HTTP request)

## Stage 3: MCP Subsystem Integration

The next stage moves the declarative approach into WildFly's MCP subsystem itself. The subsystem would parse `mcp-tools.xml` at deployment time, programmatically register tools (bypassing `@Tool` annotations entirely), and execute HTTP calls directly.

This eliminates all Java tool classes from the application. The deployment contains only the XML descriptor and the REST backend — no `TodoTools.java`, no `HttpToolExecutor.java`, no `wildfly-mcp-api` dependency.

**Key changes (in `wildfly-ai-feature-pack/wildfly-mcp/`):**
- New `MCPToolsXmlProcessor` deployment unit processor to parse `mcp-tools.xml`
- Extended `WildFlyMCPRegistry` to store HTTP tool definitions alongside annotation-based tools
- HTTP execution path in `ToolMessageHandler` for XML-defined tools
- CDI/MethodHandle pipeline skipped for tools with no Java class

The `[./plan-declarative-mcp-subsystem.md](plan-declarative-mcp-subsystem.md)` describes what this approach would require.

### Summary

* (+) No modification to the existing todo-backend.war
* (+) No new Java code required, the mcp-tools.xml would be deployed directly to WildFly
* (-) The Tool API is constrained by the HTTP API (there is a 1:1 mapping between MCP Tool and HTTP request)
* (-) Templating might be too simplistic and may require some Java runtime hooks to handle the I/O mapping between MCP and the HTTP API)
