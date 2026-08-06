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
    private static final int MAX_BINDINGS_PER_CALL = 10_000;
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
        sources = sources == null ? Map.of() : sources;
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

        // A macro caller often prepares its storage in another function first:
        // `show_activity -> request/load_names -> activity_idle_dynamic`.  The first index above
        // deliberately remains a cheap same-function scan for compatibility, but it cannot see
        // those ordered effects.  Replay every function with a symbolic storage environment so a
        // call site receives the values that were written by its preceding function calls too.
        addDataflowInvocations(
                sources, macros, invocationParser, storageAssignmentParser, invocations, callers);

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
            if (candidates.isEmpty()) {
                for (String macro : targetMacros) {
                    unresolved.add(invocation.target() + "|" + macro);
                }
                continue;
            }

            for (Map<String, String> fields : candidates) {
                if (fields == null || fields.isEmpty()) {
                    for (String macro : targetMacros) {
                        unresolved.add(invocation.target() + "|" + macro);
                    }
                    continue;
                }
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

    private static void addDataflowInvocations(
            Map<String, String> sources,
            Map<String, Set<String>> macros,
            InvocationParser invocationParser,
            StorageAssignmentParser storageAssignmentParser,
            List<Invocation> invocations,
            Map<String, Set<String>> callers) {
        if (invocationParser == null) return;

        Map<String, List<ProgramCommand>> programs = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String function = normalizeFunctionId(entry.getKey());
            String source = entry.getValue() == null ? "" : entry.getValue();
            List<String> physicalLines = FunctionSource.parse(source).lines();
            List<ProgramCommand> commands = new ArrayList<>();
            for (int lineIndex = 0; lineIndex < physicalLines.size(); lineIndex++) {
                FunctionSource.LogicalCommand logical =
                        FunctionSource.LogicalCommand.read(physicalLines, lineIndex);
                lineIndex = logical.lastLineIndex();
                String line = logical.value();
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                StorageAssignment assignment =
                        storageAssignmentParser == null
                                ? null
                                : storageAssignmentParser.parse(line);
                ParsedInvocation invocation = invocationParser.parse(line);
                if (assignment != null || invocation != null) {
                    commands.add(
                            new ProgramCommand(
                                    assignment, invocation, conditionClauses(line)));
                }
            }
            programs.put(function, List.copyOf(commands));
        }

        // Start at the immediate callers of macro functions.  The values needed by a macro are
        // defined immediately before that call in normal datapacks (often by a small loader
        // function); starting at pack roots such as `tick` would replay the entire game graph and
        // mix unrelated runtime states into one static binding set.
        Set<String> roots = new LinkedHashSet<>();
        for (Map.Entry<String, List<ProgramCommand>> entry : programs.entrySet()) {
            if (entry.getValue().stream()
                    .map(ProgramCommand::invocation)
                    .filter(java.util.Objects::nonNull)
                    .map(invocation -> normalizeFunctionId(invocation.target()))
                    .anyMatch(target -> !macros.getOrDefault(target, Set.of()).isEmpty())) {
                roots.add(entry.getKey());
            }
        }

        for (String root : roots) {
            executeFunction(
                    root,
                    programs,
                    macros,
                    new StorageEnvironment(),
                    List.of(),
                    new LinkedHashSet<>(),
                    invocations,
                    callers);
        }
    }

    private static void executeFunction(
            String function,
            Map<String, List<ProgramCommand>> programs,
            Map<String, Set<String>> macros,
            StorageEnvironment environment,
            List<Predicate> inheritedConditions,
            Set<String> activeFunctions,
            List<Invocation> invocations,
            Map<String, Set<String>> callers) {
        String normalized = normalizeFunctionId(function);
        List<ProgramCommand> commands = programs.get(normalized);
        if (commands == null || !activeFunctions.add(normalized)) return;

        for (ProgramCommand command : commands) {
            List<Predicate> conditions =
                    combineConditions(inheritedConditions, command.conditions());
            if (command.assignment() != null) {
                environment.add(command.assignment(), conditions);
            }

            ParsedInvocation invocation = command.invocation();
            if (invocation == null) continue;

            List<Map<String, String>> callerValues =
                    invocation.storageId() == null
                            ? List.of()
                            : snapshotBindings(
                                    environment,
                                    invocation,
                                    conditions,
                                    macros.getOrDefault(
                                            normalizeFunctionId(invocation.target()), Set.of()));
            addInvocation(
                    invocations,
                    callers,
                    normalized,
                    invocation,
                    callerValues);

            // Functions without `with storage` are still important: they commonly populate the
            // storage that the next command passes to a macro.  A called function may also contain
            // another `with storage` call, so recurse for both forms.  The active set prevents
            // recursive datapack functions from making the static pass diverge.
            executeFunction(
                    invocation.target(),
                    programs,
                    macros,
                    environment,
                    conditions,
                    activeFunctions,
                    invocations,
                    callers);
        }
        activeFunctions.remove(normalized);
    }

    private static List<Map<String, String>> snapshotBindings(
            StorageEnvironment environment,
            ParsedInvocation invocation,
            List<Predicate> invocationConditions,
            Set<String> targetMacros) {
        String storageId = invocation.storageId();
        String basePath = invocation.path() == null ? "" : invocation.path();
        if (targetMacros.isEmpty()) return List.of();

        Map<String, List<ValueCandidate>> candidates = new LinkedHashMap<>();
        for (String macro : targetMacros) {
            List<ValueCandidate> values =
                    environment.candidates(storageId, basePath, macro, invocationConditions);
            if (values.isEmpty() && macro.equals(lastPathSegment(basePath))) {
                values =
                        environment.candidates(
                                storageId, basePath, "$value", invocationConditions);
            }
            if (!values.isEmpty()) candidates.put(macro, values);
        }
        if (candidates.isEmpty()) return List.of();

        List<String> fields = new ArrayList<>(candidates.keySet());
        List<Map<String, String>> result = new ArrayList<>();
        collectBindings(
                candidates,
                fields,
                0,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                result);
        return List.copyOf(result);
    }

    private static void collectBindings(
            Map<String, List<ValueCandidate>> candidates,
            List<String> fields,
            int index,
            Map<String, String> binding,
            Map<String, ValueCandidate> selected,
            List<Map<String, String>> result) {
        if (index >= fields.size()) {
            if (!binding.isEmpty() && !result.contains(binding)) {
                result.add(Map.copyOf(binding));
            }
            return;
        }

        String field = fields.get(index);
        for (ValueCandidate candidate : candidates.getOrDefault(field, List.of())) {
            if (!compatibleWithSelected(candidate, field, selected, candidates)) continue;
            binding.put(field, candidate.value());
            selected.put(field, candidate);
            collectBindings(candidates, fields, index + 1, binding, selected, result);
            selected.remove(field);
            binding.remove(field);
            if (result.size() >= MAX_BINDINGS_PER_CALL) return;
        }
    }

    private static boolean compatibleWithSelected(
            ValueCandidate candidate,
            String field,
            Map<String, ValueCandidate> selected,
            Map<String, List<ValueCandidate>> candidates) {
        for (ValueCandidate other : selected.values()) {
            if (!conditionsCompatible(candidate.conditions(), other.conditions())) return false;
        }

        // Conditional storage setup normally writes a related group in separate commands, for
        // example req1_name and req1_color under the same score predicate.  If another field has a
        // candidate from that exact group, selecting its default/other group would create an
        // impossible name/color pair.  Independent score predicates remain combinable.
        String group = candidate.conditionKey();
        for (Map.Entry<String, ValueCandidate> entry : selected.entrySet()) {
            if (group.isBlank()) {
                String selectedGroup = entry.getValue().conditionKey();
                if (!selectedGroup.isBlank()
                        && !entry.getKey().equals(field)
                        && candidates.getOrDefault(field, List.of()).stream()
                                .anyMatch(value -> selectedGroup.equals(value.conditionKey()))) {
                    return false;
                }
            } else if (!entry.getKey().equals(field)
                    && candidates.getOrDefault(entry.getKey(), List.of()).stream()
                            .anyMatch(value -> group.equals(value.conditionKey()))
                    && !group.equals(entry.getValue().conditionKey())) {
                return false;
            }
        }
        return true;
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

    private static List<Predicate> combineConditions(
            List<Predicate> inherited, List<Predicate> local) {
        List<Predicate> result = new ArrayList<>(inherited);
        for (Predicate predicate : local) {
            if (!result.contains(predicate)) result.add(predicate);
        }
        return List.copyOf(result);
    }

    private static boolean compatible(
            List<Predicate> left, List<Predicate> right) {
        return conditionsCompatible(left, right);
    }

    private static boolean conditionsCompatible(
            List<Predicate> left, List<Predicate> right) {
        for (Predicate first : left) {
            for (Predicate second : right) {
                if (!first.compatible(second)) return false;
            }
        }
        return true;
    }

    /**
     * Extracts the execute prefix without interpreting the command that follows {@code run}.
     * The prefix is only a correlation hint for static values; Brigadier remains the authority for
     * command syntax and SNBT parsing.
     */
    private static List<Predicate> conditionClauses(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (!trimmed.startsWith("execute ")) return List.of();

        int run = executeRunIndex(trimmed);
        if (run < 0) return List.of();
        String prefix = trimmed.substring("execute ".length(), run).trim();
        if (prefix.isEmpty()) return List.of();

        List<String> tokens = commandTokens(prefix);
        List<Predicate> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= tokens.size(); i++) {
            if (i < tokens.size()
                    && !"if".equals(tokens.get(i))
                    && !"unless".equals(tokens.get(i))) {
                continue;
            }
            if (i > start) result.add(Predicate.parse(String.join(" ", tokens.subList(start, i))));
            start = i;
        }
        if (result.isEmpty()) result.add(Predicate.parse(prefix));
        return List.copyOf(result);
    }

    private static int executeRunIndex(String command) {
        char quote = 0;
        boolean escaped = false;
        int squareDepth = 0;
        int compoundDepth = 0;
        for (int i = 0; i < command.length() - 4; i++) {
            char character = command.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == quote) quote = 0;
                continue;
            }
            if (character == '\'' || character == '"') {
                quote = character;
                continue;
            }
            if (character == '[') squareDepth++;
            else if (character == ']') squareDepth = Math.max(0, squareDepth - 1);
            else if (character == '{') compoundDepth++;
            else if (character == '}') compoundDepth = Math.max(0, compoundDepth - 1);
            if (squareDepth == 0 && compoundDepth == 0
                    && command.startsWith(" run ", i)) return i;
        }
        return -1;
    }

    /** Splits command clauses without breaking quoted or structured arguments. */
    private static List<String> commandTokens(String command) {
        if (command == null || command.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        int start = -1;
        int squareDepth = 0;
        int compoundDepth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char character = command.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == quote) quote = 0;
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '[') {
                squareDepth++;
            } else if (character == ']') {
                squareDepth = Math.max(0, squareDepth - 1);
            } else if (character == '{') {
                compoundDepth++;
            } else if (character == '}') {
                compoundDepth = Math.max(0, compoundDepth - 1);
            }

            if (Character.isWhitespace(character)
                    && quote == 0
                    && squareDepth == 0
                    && compoundDepth == 0) {
                if (start >= 0) {
                    tokens.add(command.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) tokens.add(command.substring(start));
        return List.copyOf(tokens);
    }

    private static String joinPath(String base, String child) {
        String first = base == null ? "" : base.trim();
        String second = child == null ? "" : child.trim();
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        return first + "." + second;
    }

    private static final class StorageEnvironment {
        private final Map<String, List<Write>> writes = new LinkedHashMap<>();

        void add(StorageAssignment assignment, List<Predicate> conditions) {
            if (assignment == null
                    || assignment.storageId() == null
                    || assignment.values() == null) return;

            for (Map.Entry<String, String> entry : assignment.values().entrySet()) {
                String relative = "$value".equals(entry.getKey()) ? "" : entry.getKey();
                String path = joinPath(assignment.path(), relative);
                Write write =
                        new Write(
                                assignment.storageId(),
                                path,
                                entry.getValue(),
                                List.copyOf(conditions));
                List<Write> values = writes.computeIfAbsent(assignment.storageId(), ignored -> new ArrayList<>());
                if (!values.contains(write)) values.add(write);
            }
        }

        List<ValueCandidate> candidates(
                String storageId,
                String basePath,
                String field,
                List<Predicate> invocationConditions) {
            String wanted = "$value".equals(field) ? basePath : joinPath(basePath, field);
            List<ValueCandidate> result = new ArrayList<>();
            for (Write write : writes.getOrDefault(storageId, List.of())) {
                if (!write.path().equals(wanted)) continue;
                if (!compatible(write.conditions(), invocationConditions)) continue;
                ValueCandidate candidate = new ValueCandidate(write.value(), write.conditions());
                if (!result.contains(candidate)) result.add(candidate);
            }
            return List.copyOf(result);
        }
    }

    private record ProgramCommand(
            StorageAssignment assignment,
            ParsedInvocation invocation,
            List<Predicate> conditions) {}

    private record Write(
            String storageId,
            String path,
            String value,
            List<Predicate> conditions) {}

    private record ValueCandidate(String value, List<Predicate> conditions) {
        String conditionKey() {
            return conditions.stream().map(Predicate::text).reduce((left, right) -> left + " && " + right).orElse("");
        }
    }

    private record Predicate(
            String text,
            String domain,
            boolean negated,
            boolean score,
            long minimum,
            long maximum) {
        static Predicate parse(String raw) {
            List<String> tokens = commandTokens(raw);
            String text = String.join(" ", tokens);
            boolean negated = !tokens.isEmpty() && "unless".equals(tokens.getFirst());
            if (tokens.size() >= 5 && "score".equals(tokens.get(1))) {
                String domain = "score:" + tokens.get(2) + ":" + tokens.get(3);
                int matches = -1;
                for (int i = 4; i < tokens.size(); i++) {
                    if ("matches".equals(tokens.get(i)) && i + 1 < tokens.size()) {
                        matches = i + 1;
                        break;
                    }
                }
                if (matches >= 0) {
                    long[] range = parseRange(tokens.get(matches));
                    return new Predicate(text, domain, negated, true, range[0], range[1]);
                }
                return new Predicate(text, domain, negated, true, Long.MIN_VALUE, Long.MAX_VALUE);
            }
            String domain =
                    tokens.size() > 1
                            ? tokens.getFirst() + ":" + String.join(" ", tokens.subList(1, tokens.size()))
                            : text;
            return new Predicate(text, domain, negated, false, Long.MIN_VALUE, Long.MAX_VALUE);
        }

        boolean compatible(Predicate other) {
            if (!domain.equals(other.domain)) return true;
            if (!score || !other.score) return text.equals(other.text);
            if (!negated && !other.negated) {
                return rangesOverlap(minimum, maximum, other.minimum, other.maximum);
            }
            if (negated && other.negated) return true;
            Predicate positive = negated ? other : this;
            Predicate negative = negated ? this : other;
            if (positive.minimum == Long.MIN_VALUE || positive.maximum == Long.MAX_VALUE) return true;
            return positive.minimum < negative.minimum
                    || positive.maximum > negative.maximum;
        }

        private static long[] parseRange(String value) {
            try {
                int separator = value.indexOf("..");
                if (separator >= 0) {
                    String minimumText = value.substring(0, separator);
                    String maximumText = value.substring(separator + 2);
                    long minimum =
                            minimumText.isEmpty()
                                    ? Long.MIN_VALUE
                                    : Long.parseLong(minimumText);
                    long maximum =
                            maximumText.isEmpty()
                                    ? Long.MAX_VALUE
                                    : Long.parseLong(maximumText);
                    return new long[] {minimum, maximum};
                }
                long exact = Long.parseLong(value);
                return new long[] {exact, exact};
            } catch (NumberFormatException ignored) {
                return new long[] {Long.MIN_VALUE, Long.MAX_VALUE};
            }
        }

        private static boolean rangesOverlap(long firstMin, long firstMax, long secondMin, long secondMax) {
            return firstMin <= secondMax && secondMin <= firstMax;
        }
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
