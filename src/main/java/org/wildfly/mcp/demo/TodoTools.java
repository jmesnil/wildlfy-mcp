package org.wildfly.mcp.demo;

import java.util.Map;

import org.wildfly.mcp.api.Tool;
import org.wildfly.mcp.api.ToolArg;

public class TodoTools {

    private final HttpToolExecutor executor = HttpToolExecutor.getInstance();

    @Tool(description = "List all todo items. Returns a JSON array of objects with fields: id (number), title (string), completed (boolean).")
    public String listTodos() {
        return executor.execute("listTodos", Map.of());
    }

    @Tool(description = "Add a new todo item to the list.")
    public String addTodo(@ToolArg(description = "The title of the todo item") String title) {
        return executor.execute("addTodo", Map.of("title", title));
    }

    @Tool(description = "Mark a todo item as completed.")
    public String completeTodo(@ToolArg(description = "The ID of the todo item to complete") long id) {
        return executor.execute("completeTodo", Map.of("id", String.valueOf(id)));
    }

    @Tool(description = "Delete a todo item from the list.")
    public String deleteTodo(@ToolArg(description = "The ID of the todo item to delete") long id) {
        return executor.execute("deleteTodo", Map.of("id", String.valueOf(id)));
    }
}
