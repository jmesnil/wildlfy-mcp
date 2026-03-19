package org.wildfly.mcp.demo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.wildfly.mcp.api.Tool;
import org.wildfly.mcp.api.ToolArg;

import java.io.StringReader;
import java.util.stream.Collectors;

public class TodoTools {

    private static final String BASE_URL = "http://localhost:8080/todo-backend";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Tool(description = "Add a new todo item to the list.")
    public String addTodo(@ToolArg(description = "The title of the todo item") String title) {
        String json = Json.createObjectBuilder()
                .add("title", title)
                .build()
                .toString();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject todo = parseObject(response.body());
            return "Added todo: " + formatTodo(todo);
        } catch (IOException | InterruptedException e) {
            return "Failed to add todo: " + e.getMessage();
        }
    }

    @Tool(description = "List all todo items.")
    public String listTodos() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .GET()
                .build();
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            JsonArray todos = parseArray(response.body());
            if (todos.isEmpty()) {
                return "No todos found.";
            }
            return todos.stream()
                    .map(v -> formatTodo(v.asJsonObject()))
                    .collect(Collectors.joining("\n"));
        } catch (IOException | InterruptedException e) {
            return "Failed to list todos: " + e.getMessage();
        }
    }

    @Tool(description = "Mark a todo item as completed.")
    public String completeTodo(@ToolArg(description = "The ID of the todo item to complete") long id) {
        String json = Json.createObjectBuilder()
                .add("completed", true)
                .build()
                .toString();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/" + id))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return "Todo with id " + id + " not found.";
            }
            JsonObject todo = parseObject(response.body());
            return "Completed: " + formatTodo(todo);
        } catch (IOException | InterruptedException e) {
            return "Failed to complete todo: " + e.getMessage();
        }
    }

    @Tool(description = "Delete a todo item from the list.")
    public String deleteTodo(@ToolArg(description = "The ID of the todo item to delete") long id) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/" + id))
                .DELETE()
                .build();
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return "Todo with id " + id + " not found.";
            }
            return "Deleted todo with id " + id;
        } catch (IOException | InterruptedException e) {
            return "Failed to delete todo: " + e.getMessage();
        }
    }

    private static String formatTodo(JsonObject todo) {
        boolean completed = todo.getBoolean("completed", false);
        long id = todo.getJsonNumber("id").longValue();
        String title = todo.getString("title");
        return "[%s] #%d: %s".formatted(completed ? "x" : " ", id, title);
    }

    private static JsonObject parseObject(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }

    private static JsonArray parseArray(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readArray();
        }
    }
}
