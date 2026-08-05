package me.fengming.wtem.common.core.extraction.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.core.extraction.manifest.ExtractionManifest;
import me.fengming.wtem.common.core.extraction.manifest.ExtractionRecord;
import net.minecraft.SharedConstants;

/** Renders a machine-readable description of one extraction run.
 *
 * @author FengMing
 */
public final class ExtractionSchema {
    public static final int VERSION = 2;

    private ExtractionSchema() {}

    public static String render(WtemConfig config, List<ExtractionRecord> records) {
        JsonObject schema = new JsonObject();
        schema.addProperty("schema_version", VERSION);
        schema.addProperty("mod", Wtem.MOD_ID);
        schema.addProperty("minecraft_version", minecraftVersion());
        schema.addProperty("language_file", config.languageFile());
        schema.addProperty("manifest_file", ExtractionManifest.fileName(config.languageFile()));

        JsonArray stages = new JsonArray();
        for (WtemConfig.Stage stage : WtemConfig.Stage.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", stage.id());
            item.addProperty("enabled", config.isEnabled(stage));
            stages.add(item);
        }
        schema.add("stages", stages);

        JsonObject resources = new JsonObject();
        config.resources().forEach(resources::addProperty);
        schema.add("resources", resources);

        JsonObject filters = new JsonObject();
        addStrings(filters, "region", config.filters().region());
        addStrings(filters, "datapack", config.filters().datapack());
        addStrings(filters, "storage", config.filters().storage());
        addStrings(filters, "entity", config.filters().entity());
        addStrings(filters, "block_entity", config.filters().blockEntity());
        addStrings(filters, "datapack_excluded_paths", config.skippedPaths());
        JsonObject selection = new JsonObject();
        addStrings(selection, "datapacks", config.filters().selection().datapacks());
        addStrings(selection, "entities", config.filters().selection().entities());
        addStrings(
                selection,
                "block_entities",
                config.filters().selection().blockEntities());
        addStrings(
                selection,
                "storage_files",
                config.filters().selection().storageFiles());
        filters.add("selection", selection);
        schema.add("filters", filters);

        JsonObject outputs = new JsonObject();
        outputs.addProperty("region_snbt", config.outputs().exportRegionSnbt());
        outputs.addProperty("schema", config.outputs().exportSchema());
        outputs.addProperty("region_snbt_directory", config.outputs().regionSnbtDirectory());
        outputs.addProperty("schema_file", config.outputs().schemaFile());
        schema.add("outputs", outputs);
        JsonObject ai = new JsonObject();
        ai.addProperty("translation_enabled", config.aiTranslation().enabled());
        ai.addProperty("translation_usable", config.aiTranslation().usable());
        ai.addProperty("key_naming_enabled", config.keyNaming().scheme() == WtemConfig.KeyNaming.Scheme.AI);
        ai.addProperty("model", config.aiTranslation().model());
        ai.addProperty("target_language", config.aiTranslation().targetLanguage());
        ai.addProperty("translation_prompt", config.aiTranslation().translationPrompt());
        ai.addProperty("key_naming_prompt", config.aiTranslation().keyNamingPrompt());
        schema.add("ai", ai);
        schema.addProperty("key_naming_scheme", config.keyNaming().scheme().id());
        schema.addProperty("resource_pack_enabled", config.resourcePack().enabled());

        JsonArray columns = new JsonArray();
        ExtractionManifest.COLUMNS.forEach(columns::add);
        schema.add("manifest_columns", columns);
        schema.addProperty("record_count", records.size());
        schema.addProperty("language_entry_count", TranslationContext.snapshot().size());
        return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(schema);
    }

    private static void addStrings(JsonObject object, String name, List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        object.add(name, array);
    }

    private static String minecraftVersion() {
        try {
            SharedConstants.tryDetectVersion();
            Object version = SharedConstants.getCurrentVersion();
            for (String methodName : new String[] {"name", "getName"}) {
                try {
                    Object value = version.getClass().getMethod(methodName).invoke(version);
                    if (value instanceof String text) return text;
                } catch (ReflectiveOperationException ignored) {
                    // Try the accessor name used by the other mapping generation.
                }
            }
            return "unknown";
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }
}
