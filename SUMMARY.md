# MCP WildFly Demo — Project Summary

This project explores how to expose a REST backend as MCP tools using WildFly's MCP subsystem, progressively moving from application-level code to a fully declarative, server-managed approach.

The REST backend (`todo-backend.war`) is a pre-existing JPA-backed TODO application that is never modified. All stages build MCP tooling on top of it.

## Stage 1: Setup

A WildFly application is configured with the `mcp-server` subsystem layer and deployed alongside the existing `todo-backend.war`. The todo backend exposes a standard REST API at `http://localhost:8080/todo-backend/` with CRUD operations for todo items.

**Key files:**
- `todo-backend.war` — pre-existing REST backend (untouched)
- `pom.xml` — WildFly Maven plugin with `mcp-server`, `jaxrs`, `jpa` layers

## Stage 2: @Tool Annotations with HTTP Calls

MCP tools are implemented as Java methods annotated with `@Tool` and `@ToolArg` from the `wildfly-mcp-api`. Each method manually constructs HTTP requests using `java.net.http.HttpClient` to call the todo-backend REST API.

Four tools are exposed: `listTodos`, `addTodo`, `completeTodo`, `deleteTodo`.

**Key files:**
- `src/main/java/org/wildfly/mcp/demo/TodoTools.java` — annotated methods with inline HTTP logic

**Commits:** `014e6de`, `f6ac0aa`, `8d14bb1`

## Stage 3: Declarative XML + Application-side Executor

The hardcoded HTTP logic is extracted into a declarative XML file (`mcp-tools.xml`) that defines each tool's name, description, arguments, HTTP method, URL path, and request body template with `${arg}` placeholders.

A generic `HttpToolExecutor` parses the XML at startup and executes HTTP calls based on the definitions. `TodoTools.java` becomes thin delegates — the `@Tool` annotations remain (required by WildFly MCP for registration) but all HTTP logic is configuration-driven.

**Key files:**
- `src/main/resources/mcp-tools.xml` — declarative tool definitions
- `src/main/java/org/wildfly/mcp/demo/HttpToolExecutor.java` — XML parser + HTTP executor
- `src/main/java/org/wildfly/mcp/demo/TodoTools.java` — simplified delegates

**Commit:** `60361a8`

## Stage 4: MCP Subsystem Integration (Planned)

The final stage moves the declarative approach into WildFly's MCP subsystem itself. The subsystem would parse `mcp-tools.xml` at deployment time, programmatically register tools (bypassing `@Tool` annotations entirely), and execute HTTP calls directly.

This eliminates all Java tool classes from the application. The deployment contains only the XML descriptor and the REST backend — no `TodoTools.java`, no `HttpToolExecutor.java`, no `wildfly-mcp-api` dependency.

**Key changes (in `wildfly-ai-feature-pack/wildfly-mcp/`):**
- New `MCPToolsXmlProcessor` deployment unit processor to parse `mcp-tools.xml`
- Extended `WildFlyMCPRegistry` to store HTTP tool definitions alongside annotation-based tools
- HTTP execution path in `ToolMessageHandler` for XML-defined tools
- CDI/MethodHandle pipeline skipped for tools with no Java class

**Plan:** `plan-declarative-mcp-subsystem.md`

**Commits:** `33c89e9`, `7d4ebeb`
