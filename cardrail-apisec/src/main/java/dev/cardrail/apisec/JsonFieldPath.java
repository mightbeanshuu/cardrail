package dev.cardrail.apisec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.cardrail.apisec.JweException.Reason;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The subset of JSONPath this module accepts: {@code $} (the document root) or {@code $.a.b.c}, where each
 * segment is {@code [A-Za-z0-9_-]+}. No wildcards, filters, array indexes or bracket notation, so every path
 * names at most one member of nested objects.
 */
record JsonFieldPath(String expression, List<String> segments) {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");

    static JsonFieldPath parse(String expression) {
        if ("$".equals(expression)) {
            return new JsonFieldPath(expression, List.of());
        }
        if (expression == null || !expression.startsWith("$.")) {
            throw new IllegalArgumentException("path must be $ or $.name[.name...]: " + expression);
        }
        String[] parts = expression.substring(2).split("\\.", -1);
        for (String p : parts) {
            if (!SEGMENT.matcher(p).matches()) {
                throw new IllegalArgumentException("invalid path segment '" + p + "' in " + expression);
            }
        }
        return new JsonFieldPath(expression, List.of(parts));
    }

    boolean isRoot() {
        return segments.isEmpty();
    }

    String leaf() {
        return segments.getLast();
    }

    /** The node at this path, or {@code null} if any segment is absent. */
    JsonNode find(ObjectNode root) {
        JsonNode node = root;
        for (String s : segments) {
            if (!node.isObject()) {
                throw new JweException(Reason.INVALID_PAYLOAD, "expected an object on the way to " + expression);
            }
            node = node.get(s);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /** The object that holds {@link #leaf()}, or {@code null} if it does not exist. */
    ObjectNode findParent(ObjectNode root) {
        JsonNode node = root;
        for (String s : segments.subList(0, segments.size() - 1)) {
            node = node.get(s);
            if (node == null) {
                return null;
            }
            if (!node.isObject()) {
                throw new JweException(Reason.INVALID_PAYLOAD, "expected an object on the way to " + expression);
            }
        }
        return (ObjectNode) node;
    }

    /** The object at this path, creating it and any missing ancestors. */
    ObjectNode findOrCreateObject(ObjectNode root) {
        ObjectNode node = root;
        for (String s : segments) {
            JsonNode child = node.get(s);
            if (child == null) {
                node = node.putObject(s);
            } else if (child.isObject()) {
                node = (ObjectNode) child;
            } else {
                throw new JweException(Reason.INVALID_PAYLOAD, "expected an object at " + expression);
            }
        }
        return node;
    }

    /** The object that should hold {@link #leaf()}, creating it and any missing ancestors. */
    ObjectNode findOrCreateParent(ObjectNode root) {
        return new JsonFieldPath(expression, segments.subList(0, segments.size() - 1)).findOrCreateObject(root);
    }

    @Override
    public String toString() {
        return expression;
    }
}
