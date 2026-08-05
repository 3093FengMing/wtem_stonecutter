package me.fengming.wtem.common.core.extraction.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.extraction.service.ExtractionSession;
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
    private static final Set<String> COMPONENT_NAMES =
            Set.of(
                    "name",
                    "custom_name",
                    "customname",
                    "title",
                    "subtitle",
                    "description",
                    "text",
                    "message",
                    "label",
                    "display_name",
                    "displayname",
                    "lore",
                    "messages",
                    "pages",
                    "lines",
                    "raw",
                    "front_text",
                    "prompt",
                    "tooltip",
                    "error_message");

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
        if (!this.config.filters().selection().matchesStorageFile(fileName)) return;
        try (var fileTransaction = TranslationContext.beginTransaction();
                InputStream input = Files.newInputStream(file)) {
            int fileRecordsBefore = TranslationContext.recordCount();
            CompoundTag root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
            CompoundTag entries = contentRoot(root);
            boolean changed = false;
            for (String key : sortedKeys(entries)) {
                if (this.session.isCancellationRequested()) return;
                String location = fileName + "/" + key;
                try (var transaction = TranslationContext.beginTransaction();
                        var ignored = TranslationContext.pushLocation(location);
                        var subject = TranslationContext.pushSubject(key)) {
                    int recordsBefore = TranslationContext.recordCount();
                    boolean entryChanged = visit(entries, key, entries.get(key), fileName, key);
                    if (!entryChanged
                            && !TranslationContext.hasOnlyCatalogEntriesSince(recordsBefore)) {
                        continue;
                    }
                    transaction.commit();
                    changed |= entryChanged;
                } catch (RuntimeException exception) {
                    this.session.diagnostics().record("saved_data", location, exception);
                    Wtem.LOGGER.warn("Failed to process saved-data entry {}", location, exception);
                }
            }
            if (!changed) {
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
            CompoundTag parent, String name, Tag value, String fileName, String keyPath) {
        if (value == null) return false;
        ChangeTracker tracker = new ChangeTracker();
        String normalized = name.toLowerCase(Locale.ROOT);
        String location = fileName + "/" + keyPath.replace('.', '/');
        if (isLikelyComponent(normalized, value)
                && this.config.filters().matchesStorage(fileName, location)) {
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
                                keyPath + "." + child));
            }
        } else if (value instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                Tag child = list.get(i);
                String childPath = keyPath + "." + i;
                if (child instanceof CompoundTag childCompound) {
                    for (String childName : sortedKeys(childCompound)) {
                        tracker.add(
                                visit(
                                        childCompound,
                                        childName,
                                        childCompound.get(childName),
                                        fileName,
                                        childPath + "." + childName));
                    }
                } else if (isLikelyComponent(normalized, child)
                        && this.config
                                .filters()
                                .matchesStorage(
                                        fileName,
                                        fileName + "/" + childPath.replace('.', '/'))) {
                    String key = safeKeyPath(fileName, childPath);
                    if (child instanceof StringTag) {
                        String text = NbtUtils.getString(list, i);
                        if (!looksLikeSerializedJson(text)) {
                            addCatalogString(text, key);
                            continue;
                        }
                    }
                    tracker.add(TranslationUtils.translateNbtComponent(list, i, key));
                }
            }
        }
        return tracker.isChanged();
    }

    private static List<String> sortedKeys(CompoundTag tag) {
        return new ArrayList<>(NbtUtils.getKeys(tag)).stream().sorted().toList();
    }

    private static String safeKeyPath(String fileName, String keyPath) {
        String fileStem =
                fileName.substring(0, fileName.length() - ".dat".length());
        return (fileStem + "." + keyPath).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void addCatalogString(String text, String keyPath) {
        if (text == null || text.isBlank()) return;
        try (var ignored = TranslationContext.push(keyPath)) {
            TranslationContext.addCatalogEntry(text);
        }
    }

    private static boolean looksLikeSerializedJson(String text) {
        if (text == null) return false;
        String value = text.trim();
        return !value.isEmpty()
                && (value.charAt(0) == '{' || value.charAt(0) == '[' || value.charAt(0) == '"');
    }

    private static boolean isLikelyComponent(String name, Tag value) {
        if (COMPONENT_NAMES.contains(name)) return true;
        if (!(value instanceof CompoundTag compound)) return false;
        return compound.contains("text")
                || compound.contains("translate")
                || compound.contains("type") && compound.contains("value");
    }
}
