package me.fengming.wtem.common.core.handler.datapack.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A conservative static index of macro functions, caller sites, and correlated arguments.
 *
 * <p>Minecraft supplies macro arguments at runtime (most commonly through {@code with storage}),
 * so an extractor cannot evaluate every call.  It can, however, recover literal values written to
 * storage in the same data pack and follow calls that read those values.  This index keeps that
 * useful subset from commands already accepted by Minecraft's parsers. Each call site keeps its
 * argument map intact so values from different callers are never combined into a fictitious
 * Cartesian product; an unresolved call never becomes a guessed value.
 *
 * @author FengMing
 */
final class MacroCallGraph {
    private final Map<String, Set<String>> macrosByFunction;
    private final Map<String, Map<String, Set<String>>> valuesByFunction;
    private final Map<String, List<Map<String, String>>> bindingsByFunction;
    private final Map<String, Set<String>> callersByFunction;
    private final Set<String> unresolvedFunctions;

    @FunctionalInterface
    interface InvocationParser {
        ParsedInvocation parse(String line);
    }

    @FunctionalInterface
    interface StorageAssignmentParser {
        StorageAssignment parse(String line);
    }

    record ParsedInvocation(String target, String storageId, String path) {}

    /**
     * A storage write already accepted by Minecraft's command and NBT parsers.
     *
     * <p>{@code values} contains the primitive value itself under {@code $value}, and top-level
     * compound fields under their field names. Keeping this result structured means the call graph
     * never has to reinterpret command or SNBT syntax with a second grammar.
     */
    record StorageAssignment(String storageId, String path, Map<String, String> values) {}

    private MacroCallGraph(
            Map<String, Set<String>> macrosByFunction,
            Map<String, Map<String, Set<String>>> valuesByFunction,
            Map<String, List<Map<String, String>>> bindingsByFunction,
            Map<String, Set<String>> callersByFunction,
            Set<String> unresolvedFunctions) {
        this.macrosByFunction = macrosByFunction;
        this.valuesByFunction = valuesByFunction;
        this.bindingsByFunction = bindingsByFunction;
        this.callersByFunction = callersByFunction;
        this.unresolvedFunctions = unresolvedFunctions;
    }

    static MacroCallGraph build(Map<String, String> sources) {
        return build(sources, FunctionHandler::parseFunctionInvocation, FunctionHandler::parseStorageAssignment);
    }

    static MacroCallGraph build(Map<String, String> sources, InvocationParser invocationParser) {
        return build(sources, invocationParser, FunctionHandler::parseStorageAssignment);
    }

    static MacroCallGraph build(
            Map<String, String> sources,
            InvocationParser invocationParser,
            StorageAssignmentParser storageAssignmentParser) {
        Map<String, Set<String>> macros = new LinkedHashMap<>();
        Map<String, Set<String>> callers = new LinkedHashMap<>();
        Map<String, List<Map<String, String>>> storage = new LinkedHashMap<>();
        List<Invocation> invocations = new ArrayList<>();

        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String function = normalizeFunctionId(entry.getKey());
            String source = entry.getValue() == null ? "" : entry.getValue();
            Set<String> names = macroNames(source);
            macros.put(function, names);
            Map<String, List<Map<String, String>>> localStorage = new LinkedHashMap<>();

            List<String> physicalLines = FunctionSource.parse(source).lines();
            for (int lineIndex = 0; lineIndex < physicalLines.size(); lineIndex++) {
                FunctionSource.LogicalCommand logical =
                        FunctionSource.LogicalCommand.read(physicalLines, lineIndex);
                lineIndex = logical.lastLineIndex();
                String line = logical.value();
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                StorageAssignment assignment =
                        storageAssignmentParser == null ? null : storageAssignmentParser.parse(line);
                if (assignment != null && assignment.values() != null && !assignment.values().isEmpty()) {
                    addStorageAssignment(storage, assignment);
                    addStorageAssignment(localStorage, assignment);
                }

                ParsedInvocation parsed = invocationParser.parse(line);
                if (parsed != null) {
                    List<Map<String, String>> callerValues =
                            parsed.storageId() == null
                                    ? List.of()
                                    : lookupStorage(
                                            localStorage,
                                            parsed.storageId(),
                                            parsed.path(),
                                            true);
                    if (callerValues.isEmpty() && parsed.storageId() != null) {
                        callerValues =
                                lookupStorage(
                                        storage,
                                        parsed.storageId(),
                                        parsed.path(),
                                        false);
                    }
                    addInvocation(invocations, callers, function, parsed, callerValues);
                }
            }
        }

        Map<String, Map<String, Set<String>>> values = new LinkedHashMap<>();
        Map<String, List<Map<String, String>>> bindings = new LinkedHashMap<>();
        Set<String> unresolved = new LinkedHashSet<>();
        for (Invocation invocation : invocations) {
            Set<String> targetMacros = macros.getOrDefault(invocation.target(), Set.of());
            if (targetMacros.isEmpty()) continue;
            List<Map<String, String>> candidates = invocation.callerValues();
            if (candidates.isEmpty() && invocation.storageId() != null) {
                candidates =
                        lookupStorage(
                                storage,
                                invocation.storageId(),
                                invocation.path(),
                                false);
            }
            if (candidates.isEmpty()) candidates = List.of(Map.of());

            for (Map<String, String> fields : candidates) {
                Map<String, String> binding = new LinkedHashMap<>();
                for (String macro : targetMacros) {
                    String value = fields.get(macro);
                    if (value == null && fields.size() == 1 && fields.containsKey("$value")) {
                        // A primitive storage path is the argument itself when its final path
                        // segment has the same name as the macro.
                        String path = invocation.path();
                        if (path != null && macro.equals(lastPathSegment(path))) {
                            value = fields.get("$value");
                        }
                    }
                    if (value == null || value.isBlank()) {
                        unresolved.add(invocation.target() + "|" + macro);
                        continue;
                    }
                    binding.put(macro, value);
                    values.computeIfAbsent(invocation.target(), ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(macro, ignored -> new LinkedHashSet<>())
                            .add(value);
                }
                Map<String, String> immutableBinding = Map.copyOf(binding);
                List<Map<String, String>> targetBindings =
                        bindings.computeIfAbsent(invocation.target(), ignored -> new ArrayList<>());
                if (!targetBindings.contains(immutableBinding)) {
                    targetBindings.add(immutableBinding);
                }
            }
        }

        for (Map.Entry<String, Set<String>> entry : macros.entrySet()) {
            String function = entry.getKey();
            if (!entry.getValue().isEmpty()
                    && !callers.containsKey(function)) {
                for (String macro : entry.getValue()) unresolved.add(function + "|" + macro);
            }
        }
        Map<String, List<Map<String, String>>> immutableBindings = new LinkedHashMap<>();
        bindings.forEach(
                (function, functionBindings) ->
                        immutableBindings.put(function, List.copyOf(functionBindings)));
        return new MacroCallGraph(macros, values, immutableBindings, callers, unresolved);
    }

    private static void addInvocation(
            List<Invocation> invocations,
            Map<String, Set<String>> callers,
            String caller,
            ParsedInvocation parsed,
            List<Map<String, String>> callerValues) {
        String target = normalizeFunctionId(parsed.target());
        invocations.add(
                new Invocation(
                        caller,
                        target,
                        parsed.storageId(),
                        parsed.path(),
                        callerValues == null ? List.of() : List.copyOf(callerValues)));
        callers.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(caller);
    }

    Set<String> values(String function, String macro) {
        String normalized = normalizeFunctionId(function);
        return valuesByFunction.getOrDefault(normalized, Map.of()).getOrDefault(macro, Set.of());
    }

    Map<String, Set<String>> values(String function) {
        return valuesByFunction.getOrDefault(normalizeFunctionId(function), Map.of());
    }

    /** One correlated macro-argument map per statically discovered call site. */
    List<Map<String, String>> bindings(String function) {
        return bindingsByFunction.getOrDefault(normalizeFunctionId(function), List.of());
    }

    boolean unresolved(String function, String macro) {
        return unresolvedFunctions.contains(normalizeFunctionId(function) + "|" + macro);
    }

    Set<String> macros(String function) {
        return macrosByFunction.getOrDefault(normalizeFunctionId(function), Set.of());
    }

    Set<String> callers(String function) {
        return callersByFunction.getOrDefault(normalizeFunctionId(function), Set.of());
    }

    private static void addStorageAssignment(
            Map<String, List<Map<String, String>>> storage, StorageAssignment assignment) {
        String key = assignment.storageId() + "|" + assignment.path();
        Map<String, String> values = Map.copyOf(assignment.values());
        List<Map<String, String>> assignments =
                storage.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (!assignments.contains(values)) assignments.add(values);
    }

    private static List<Map<String, String>> lookupStorage(
            Map<String, List<Map<String, String>>> storage,
            String storageId,
            String path,
            boolean combineDescendantAssignments) {
        String normalizedPath = path == null ? "" : path;
        List<Map<String, String>> direct = storage.get(storageId + "|" + normalizedPath);
        if (direct != null) return List.copyOf(direct);

        // A caller can pass a child path of a compound assignment. Walk the assignment's path
        // prefixes and strip that path from the flattened compound fields. For example, a root
        // merge of {args:{name:"Alex"}} must satisfy `with storage example:data args` with the
        // binding {name=Alex}, not with the unusable scalar {$value={name:"Alex"}}.
        List<Map<String, String>> result = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, String>>> entry : storage.entrySet()) {
            String prefix = storageId + "|";
            if (!entry.getKey().startsWith(prefix)) continue;
            String assignedPath = entry.getKey().substring(prefix.length());
            String remainder;
            if (assignedPath.isEmpty()) {
                if (normalizedPath.isEmpty()) continue;
                remainder = normalizedPath;
            } else if (normalizedPath.startsWith(assignedPath + ".")) {
                remainder = normalizedPath.substring(assignedPath.length() + 1);
            } else {
                continue;
            }
            for (Map<String, String> fields : entry.getValue()) {
                Map<String, String> candidate = relativeFields(fields, remainder);
                if (!candidate.isEmpty() && !result.contains(candidate)) result.add(candidate);
            }
        }
        if (!result.isEmpty() || !combineDescendantAssignments) return List.copyOf(result);

        // The common macro setup writes one field at a time and then passes their parent compound:
        //   data modify storage example:runtime args.count set value 1
        //   data modify storage example:runtime args.color set value "red"
        //   function example:target with storage example:runtime args
        // Combine only the assignments observed before this call in the same function. If a field
        // has several possible values, do not manufacture a Cartesian product; leave the call
        // unresolved instead.
        Map<String, String> combined = new LinkedHashMap<>();
        String storagePrefix = storageId + "|";
        String childPrefix = normalizedPath.isEmpty() ? "" : normalizedPath + ".";
        for (Map.Entry<String, List<Map<String, String>>> entry : storage.entrySet()) {
            if (!entry.getKey().startsWith(storagePrefix)) continue;
            String assignedPath = entry.getKey().substring(storagePrefix.length());
            if (!assignedPath.startsWith(childPrefix)) continue;
            String child = assignedPath.substring(childPrefix.length());
            if (child.isEmpty() || child.indexOf('.') >= 0) continue;

            List<Map<String, String>> assignments = entry.getValue();
            if (assignments.size() != 1) return List.of();
            String value = assignments.getFirst().get("$value");
            if (value == null || value.isBlank()) return List.of();
            String previous = combined.putIfAbsent(child, value);
            if (previous != null && !previous.equals(value)) return List.of();
        }
        return combined.isEmpty() ? List.of() : List.of(Map.copyOf(combined));
    }

    private static Map<String, String> relativeFields(
            Map<String, String> fields, String relativePath) {
        Map<String, String> result = new LinkedHashMap<>();
        String prefix = relativePath + ".";
        String whole = fields.get(relativePath);
        if (whole != null) result.put("$value", whole);
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (!field.getKey().startsWith(prefix)) continue;
            String relative = field.getKey().substring(prefix.length());
            if (!relative.isEmpty()) result.put(relative, field.getValue());
        }
        return Map.copyOf(result);
    }

    private static String normalizeFunctionId(String id) {
        if (id == null) return "";
        return id.endsWith(".mcfunction") ? id.substring(0, id.length() - 11) : id;
    }

    private static String lastPathSegment(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /** Lexes the macro marker only; command syntax is never inferred from this scan. */
    private static Set<String> macroNames(String source) {
        Set<String> names = new LinkedHashSet<>();
        int cursor = 0;
        while (source != null && (cursor = source.indexOf("$(", cursor)) >= 0) {
            int end = source.indexOf(')', cursor + 2);
            if (end < 0) break;
            String name = source.substring(cursor + 2, end);
            if (!name.isBlank()) names.add(name);
            cursor = end + 1;
        }
        return names;
    }

    private record Invocation(
            String caller,
            String target,
            String storageId,
            String path,
            List<Map<String, String>> callerValues) {}
}
