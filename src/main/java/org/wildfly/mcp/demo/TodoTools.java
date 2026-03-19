package org.wildfly.mcp.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.wildfly.mcp.api.Tool;
import org.wildfly.mcp.api.ToolArg;

public class TodoTools {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(1);
    private static final List<Todo> TODOS = new CopyOnWriteArrayList<>();

    @Tool(description = "Add a new todo item to the list.")
    public String addTodo(@ToolArg(description = "The title of the todo item") String title) {
        Todo todo = new Todo(ID_GENERATOR.getAndIncrement(), title, false);
        TODOS.add(todo);
        return "Added todo: " + todo;
    }

    @Tool(description = "List all todo items.")
    public String listTodos() {
        if (TODOS.isEmpty()) {
            return "No todos found.";
        }
        return TODOS.stream()
                .map(Todo::toString)
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Mark a todo item as completed.")
    public String completeTodo(@ToolArg(description = "The ID of the todo item to complete") long id) {
        for (int i = 0; i < TODOS.size(); i++) {
            Todo todo = TODOS.get(i);
            if (todo.id() == id) {
                Todo completed = new Todo(todo.id(), todo.title(), true);
                TODOS.set(i, completed);
                return "Completed: " + completed;
            }
        }
        return "Todo with id " + id + " not found.";
    }

    @Tool(description = "Delete a todo item from the list.")
    public String deleteTodo(@ToolArg(description = "The ID of the todo item to delete") long id) {
        boolean removed = TODOS.removeIf(todo -> todo.id() == id);
        return removed ? "Deleted todo with id " + id : "Todo with id " + id + " not found.";
    }

    public record Todo(long id, String title, boolean completed) {
        @Override
        public String toString() {
            return "[%s] #%d: %s".formatted(completed ? "x" : " ", id, title);
        }
    }
}
