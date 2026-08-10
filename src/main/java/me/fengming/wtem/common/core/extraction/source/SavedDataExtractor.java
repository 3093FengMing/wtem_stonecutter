package me.fengming.wtem.common.core.extraction.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.extraction.pattern.DataPath;
import me.fengming.wtem.common.core.extraction.pattern.ExtractionPatterns;
import me.fengming.wtem.common.core.extraction.service.ExtractionSession;
import me.fengming.wtem.common.core.extraction.VanillaSavedDataFiles;
import me.fengming.wtem.common.util.ChangeTracker;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.ResourceIo;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Extracts text components from every compressed NBT {@code *.dat} file below world/data.
 *
 * @author FengMing
 */
public final class SavedDataExtractor {
    private final Path dataDirectory;
    private final WtemConfig config;
    private final ExtractionSession session;

    public SavedDataExtractor(Path dataDirectory, WtemConfig config, ExtractionSession session) {
        this.dataDirectory = dataDirectory.normalize();
        this.config = config;
        this.session = session;
    }

    public void extract() {
        if (!Files.isDirectory(this.dataDirectory)) return;
        for (Path file : listSavedDataFiles()) {
            if (this.session.isCancellationRequested()) return;
            extractFile(file);
        }
    }

    private void extractFile(Path file) {
        String fileName =
                this.dataDirectory.relativize(file).toString().replace('\\', '/');
        if (VanillaSavedDataFiles.isVanilla(fileName)) return;
        if (!this.config.filters().selection().matchesStorageFile(fileName)) return;
        try (var fileTransaction = TranslationContext.beginTransaction();
                InputStream input = Files.newInputStream(file)) {
            int fileRecordsBefore = TranslationContext.recordCount();
            CompoundTag root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
            CompoundTag entries = contentRoot(root);
            ChangeTracker changeTracker = new ChangeTracker();
            for (String key : sortedKeys(entries)) {
                if (this.session.isCancellationRequested()) return;
                String location = fileName + "/" + key;
                try (var transaction = TranslationContext.beginTransaction();
                        var ignored = TranslationContext.pushLocation(location);
                        var subject = TranslationContext.pushSubject(key)) {
                    int recordsBefore = TranslationContext.recordCount();
                    boolean entryChanged =
                            visit(
                                    entries,
                                    key,
                                    entries.get(key),
                                    fileName,
                                    key,
                                    List.of(DataPath.keyLocation(key)));
                    if (!entryChanged
                            && !TranslationContext.hasOnlyCatalogEntriesSince(recordsBefore)) {
                        continue;
                    }
                    transaction.commit();
                    changeTracker.add(entryChanged);
                } catch (RuntimeException exception) {
                    this.session.diagnostics().record("saved_data", location, exception);
                    Wtem.LOGGER.warn("Failed to process saved-data entry {}", location, exception);
                }
            }
            if (!changeTracker.isChanged()) {
                if (TranslationContext.hasOnlyCatalogEntriesSince(fileRecordsBefore)) {
                    fileTransaction.commit();
                }
                return;
            }
            ResourceIo.writeNbt(file, root);
            fileTransaction.commit();
            this.session.recordModifiedSavedData();
        } catch (IOException | RuntimeException exception) {
            this.session.diagnostics().record("saved_data", file.toString(), exception);
            Wtem.LOGGER.warn("Failed to extract saved-data file {}", file, exception);
        }
    }

    private List<Path> listSavedDataFiles() {
        try (var paths = Files.walk(this.dataDirectory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(
                            path ->
                                    path.getFileName()
                                            .toString()
                                            .toLowerCase(Locale.ROOT)
                                            .endsWith(".dat"))
                    .filter(
                            path ->
                                    !VanillaSavedDataFiles.isVanilla(
                                            this.dataDirectory
                                                    .relativize(path)
                                                    .toString()
                                                    .replace('\\', '/')))
                    .sorted(
                            Comparator.comparing(
                                    path ->
                                            this.dataDirectory
                                                    .relativize(path)
                                                    .toString()
                                                    .replace('\\', '/')))
                    .toList();
        } catch (IOException exception) {
            throw new ResourceIo.ResourceIoException(
                    "Failed to list saved-data files: " + this.dataDirectory, exception);
        }
    }

    private static CompoundTag contentRoot(CompoundTag root) {
        for (String wrapper : new String[] {"data", "storage"}) {
            if (root.get(wrapper) instanceof CompoundTag entries) return entries;
        }
        return root;
    }

    private boolean visit(
            CompoundTag parent,
            String name,
            Tag value,
            String fileName,
            String keyPath,
            List<DataPath.Location> dataPath) {
        if (value == null) return false;
        ChangeTracker tracker = new ChangeTracker();
        String normalized = name.toLowerCase(Locale.ROOT);
        String location = fileName + "/" + keyPath.replace('.', '/');
        boolean selected = this.config.filters().matchesStorage(fileName, location);
        ExtractionPatterns.ValueKind customKind = customKind(fileName, dataPath);
        if (value instanceof StringTag && selected) {
            // SavedData strings cannot be distinguished reliably from human-facing text without
            // knowing the owning mod's schema. Always report them for manual review, while the
            // conservative component heuristic below still controls catalog extraction.
            recordStringWarning(fileName, keyPath, NbtUtils.getString(parent, name));
        }
        boolean component =
                selected
                        && (customKind == ExtractionPatterns.ValueKind.COMPONENT
                                || customKind == null && isLikelyComponent(normalized, value));
        if (customKind == ExtractionPatterns.ValueKind.PLAIN_STRING
                && selected
                && value instanceof StringTag) {
            addCatalogString(NbtUtils.getString(parent, name), safeKeyPath(fileName, keyPath));
            return false;
        }
        if (component) {
            if (value instanceof StringTag) {
                String text = NbtUtils.getString(parent, name);
                if (!looksLikeSerializedJson(text)) {
                    addCatalogString(text, safeKeyPath(fileName, keyPath));
                    return false;
                }
            }
            tracker.add(
                    TranslationUtils.translateNbtComponent(
                            parent, name, safeKeyPath(fileName, keyPath)));
            // The component translator owns a successfully transformed subtree. Walking it again
            // would allocate duplicate keys for the same literal nodes.
            if (tracker.isChanged()) return true;
            if (value instanceof StringTag) return false;
        }

        if (value instanceof CompoundTag compound) {
            for (String child : sortedKeys(compound)) {
                tracker.add(
                        visit(
                                compound,
                                child,
                                compound.get(child),
                                fileName,
                                keyPath + "." + child,
                                append(dataPath, DataPath.keyLocation(child))));
            }
        } else if (value instanceof ListTag list) {
            tracker.add(visitList(list, normalized, fileName, keyPath, dataPath));
        }
        return tracker.isChanged();
    }

    private boolean visitList(
            ListTag list,
            String fieldName,
            String fileName,
            String keyPath,
            List<DataPath.Location> dataPath) {
        ChangeTracker tracker = new ChangeTracker();
        for (int i = 0; i < list.size(); i++) {
            Tag child = list.get(i);
            String childPath = keyPath + "." + i;
            String childLocation = fileName + "/" + childPath.replace('.', '/');
            boolean selected = this.config.filters().matchesStorage(fileName, childLocation);
            List<DataPath.Location> childDataPath = append(dataPath, DataPath.indexLocation(i));
            ExtractionPatterns.ValueKind customKind = customKind(fileName, childDataPath);
            if (child instanceof StringTag && selected) {
                recordStringWarning(fileName, childPath, NbtUtils.getString(list, i));
            }

            if (child instanceof CompoundTag compound) {
                boolean componentChanged =
                        selected
                        && (customKind == ExtractionPatterns.ValueKind.COMPONENT
                                || customKind == null
                                        && isLikelyComponent(fieldName, compound))
                                && TranslationUtils.translateNbtComponent(
                                        list, i, safeKeyPath(fileName, childPath));
                tracker.add(componentChanged);
                if (!componentChanged) {
                    for (String childName : sortedKeys(compound)) {
                        tracker.add(
                                visit(
                                        compound,
                                        childName,
                                        compound.get(childName),
                                        fileName,
                                        childPath + "." + childName,
                                        append(childDataPath, DataPath.keyLocation(childName))));
                    }
                }
            } else if (child instanceof ListTag nested) {
                tracker.add(
                        visitList(nested, fieldName, fileName, childPath, childDataPath));
            } else if (selected
                    && (customKind == ExtractionPatterns.ValueKind.PLAIN_STRING
                            || customKind == ExtractionPatterns.ValueKind.COMPONENT
                            || customKind == null && isLikelyComponent(fieldName, child))) {
                String key = safeKeyPath(fileName, childPath);
                if (child instanceof StringTag textTag) {
                    String text = NbtUtils.getStringValue(textTag);
                    if (customKind == ExtractionPatterns.ValueKind.PLAIN_STRING
                            || !looksLikeSerializedJson(text)) {
                        addCatalogString(text, key);
                        continue;
                    }
                }
                tracker.add(TranslationUtils.translateNbtComponent(list, i, key));
            }
        }
        return tracker.isChanged();
    }

    private ExtractionPatterns.ValueKind customKind(
            String fileName, List<DataPath.Location> dataPath) {
        for (ExtractionPatterns.SavedDataRule rule : this.config.patterns().savedData()) {
            if (rule.matches(fileName, dataPath)) return rule.kind();
        }
        return null;
    }

    private static List<DataPath.Location> append(
            List<DataPath.Location> path, DataPath.Location child) {
        List<DataPath.Location> result = new ArrayList<>(path.size() + 1);
        result.addAll(path);
        result.add(child);
        return List.copyOf(result);
    }

    private static List<String> sortedKeys(CompoundTag tag) {
        return new ArrayList<>(NbtUtils.getKeys(tag)).stream().sorted().toList();
    }

    private static String safeKeyPath(String fileName, String keyPath) {
        String fileStem =
                fileName.substring(0, fileName.length() - ".dat".length());
        String raw = fileStem + "." + keyPath;
        StringBuilder safe = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            safe.append(
                    isAsciiAlphaNumeric(character)
                                    || character == '.'
                                    || character == '_'
                                    || character == '-'
                            ? character
                            : '_');
        }
        return safe.toString();
    }

    private static boolean isAsciiAlphaNumeric(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9';
    }

    private static void addCatalogString(String text, String keyPath) {
        if (text == null || text.isBlank()) return;
        try (var ignored = TranslationContext.push(keyPath)) {
            TranslationContext.addCatalogEntry(text);
        }
    }

    private void recordStringWarning(String fileName, String keyPath, String text) {
        String location = fileName + "/" + keyPath.replace('.', '/');
        String kind = looksLikeSerializedJson(text) ? "serialized text component" : "plain text";
        this.session
                .diagnostics()
                .recordWarning(
                        "saved_data_string",
                        location,
                        "SavedData "
                                + kind
                                + " is stored as an NBT string and may need manual review: key "
                                + safeKeyPath(fileName, keyPath));
    }

    private static boolean looksLikeSerializedJson(String text) {
        if (text == null) return false;
        String value = text.trim();
        return !value.isEmpty()
                && (value.charAt(0) == '{' || value.charAt(0) == '[' || value.charAt(0) == '"');
    }

    private boolean isLikelyComponent(String name, Tag value) {
        if (this.config.savedDataTextFields().contains(name)) return true;
        if (!(value instanceof CompoundTag compound)) return false;
        return compound.contains("text")
                || compound.contains("translate")
                || compound.contains("score")
                || compound.contains("selector")
                || compound.contains("nbt")
                || compound.contains("keybind")
                || compound.contains("object")
                || compound.contains("type") && compound.contains("value");
    }
}
