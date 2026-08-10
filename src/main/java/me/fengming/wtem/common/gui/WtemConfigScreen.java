package me.fengming.wtem.common.gui;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.ListOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.pattern.ExtractionPatterns;
import me.fengming.wtem.common.config.WtemConfigManager;
import me.fengming.wtem.common.core.handler.datapack.ResourceHandlers;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;

/** YACL-backed editor for the complete WTEM JSON configuration.
 *
 * @author Fengming
 */
@Environment(EnvType.CLIENT)
public final class WtemConfigScreen {
    private WtemConfigScreen() {}

    public static Screen create(Screen parent) {
        Draft draft = Draft.from(WtemConfig.active());
        YetAnotherConfigLib.Builder builder =
                YetAnotherConfigLib.createBuilder()
                        .title(text("gui.wtem.config.title"))
                        .category(stages(draft))
                        .category(resources(draft))
                        .category(filters(draft))
                        .category(keys(draft))
                        .category(outputs(draft))
                        .category(aiTranslation(draft))
                        .category(resourcePack(draft))
                        .category(advanced(draft))
                        .save(() -> WtemConfigManager.saveAndActivate(draft.toConfig()));
        return builder.build().generateScreen(parent);
    }

    private static ConfigCategory stages(Draft draft) {
        ConfigCategory.Builder category = ConfigCategory.createBuilder().name(text("gui.wtem.config.stages"));
        for (WtemConfig.Stage stage : WtemConfig.Stage.values()) {
            category.option(
                    bool(
                            "gui.wtem.config.stage." + stage.id(),
                            WtemConfig.DEFAULT.isEnabled(stage),
                            () -> draft.stages.getOrDefault(stage, true),
                            value -> draft.stages.put(stage, value)));
        }
        category.option(integer("gui.wtem.config.nbt_max_depth", WtemConfig.DEFAULT.nbtMaxDepth(), () -> draft.nbtMaxDepth, value -> draft.nbtMaxDepth = value, 1, 128));
        category.option(bool("gui.wtem.config.rebuild_nested_keys", WtemConfig.DEFAULT.rebuildNestedKeys(), () -> draft.rebuildNestedKeys, value -> draft.rebuildNestedKeys = value));
        return category.build();
    }

    private static ConfigCategory resources(Draft draft) {
        ConfigCategory.Builder category = ConfigCategory.createBuilder().name(text("gui.wtem.config.resources"));
        Set<String> directories = new LinkedHashSet<>(ResourceHandlers.directories());
        directories.addAll(draft.resources.keySet());
        for (String directory : directories) {
            category.option(
                    Option.<Boolean>createBuilder()
                            .name(Component.literal(directory))
                            .description(
                                    OptionDescription.of(
                                            text("gui.wtem.config.resource.description")))
                            .binding(
                                    true,
                                    () -> draft.resources.getOrDefault(directory, true),
                                    value -> draft.resources.put(directory, value))
                            .controller(TickBoxControllerBuilder::create)
                            .build());
        }
        return category.build();
    }

    private static ConfigCategory filters(Draft draft) {
        ConfigCategory.Builder category = ConfigCategory.createBuilder().name(text("gui.wtem.config.filters"));
        category.group(list("gui.wtem.config.filters.region", WtemConfig.DEFAULT.filters().region(), () -> draft.filtersRegion, values -> draft.filtersRegion = values));
        category.group(list("gui.wtem.config.filters.datapack", WtemConfig.DEFAULT.filters().datapack(), () -> draft.filtersDatapack, values -> draft.filtersDatapack = values));
        category.group(list("gui.wtem.config.filters.storage", WtemConfig.DEFAULT.filters().storage(), () -> draft.filtersStorage, values -> draft.filtersStorage = values));
        category.group(list("gui.wtem.config.filters.entity", WtemConfig.DEFAULT.filters().entity(), () -> draft.filtersEntity, values -> draft.filtersEntity = values));
        category.group(list("gui.wtem.config.filters.block_entity", WtemConfig.DEFAULT.filters().blockEntity(), () -> draft.filtersBlockEntity, values -> draft.filtersBlockEntity = values));
        // This legacy JSON field is still supported, but it belongs to the same source-filtering
        // workflow as the newer glob rules rather than to content-policy switches.
        category.group(list("gui.wtem.config.skipped_paths", WtemConfig.DEFAULT.skippedPaths(), () -> draft.skippedPaths, values -> draft.skippedPaths = values));
        return category.build();
    }

    private static ConfigCategory keys(Draft draft) {
        ConfigCategory.Builder category = ConfigCategory.createBuilder().name(text("gui.wtem.config.keys"));
        category.option(bool("gui.wtem.config.key_reuse.default", WtemConfig.DEFAULT.keyReuse().byDefault(), () -> draft.keyReuseDefault, value -> draft.keyReuseDefault = value));
        category.group(list("gui.wtem.config.key_reuse.overrides", mapLines(WtemConfig.DEFAULT.keyReuse().overrides()), () -> draft.keyReuseOverrides, values -> draft.keyReuseOverrides = values));
        category.option(
                Option.<WtemConfig.KeyNaming.Scheme>createBuilder()
                        .name(text("gui.wtem.config.key_naming.scheme"))
                        .description(OptionDescription.of(text("gui.wtem.config.key_naming.scheme.description")))
                        .binding(
                                WtemConfig.DEFAULT.keyNaming().scheme(),
                                () -> draft.keyNamingScheme,
                                value -> draft.keyNamingScheme = value)
                        .controller(option -> EnumControllerBuilder.create(option).enumClass(WtemConfig.KeyNaming.Scheme.class))
                        .build());
        category.option(integer("gui.wtem.config.key_naming.random_length", WtemConfig.DEFAULT.keyNaming().randomLength(), () -> draft.randomLength, value -> draft.randomLength = value, 1, 32));
        category.group(list("gui.wtem.config.builtin_entries", mapLines(WtemConfig.DEFAULT.builtinEntries()), () -> draft.builtinEntries, values -> draft.builtinEntries = values));
        return category.build();
    }

    private static ConfigCategory outputs(Draft draft) {
        ConfigCategory.Builder category = ConfigCategory.createBuilder().name(text("gui.wtem.config.outputs"));
        category.option(bool("gui.wtem.config.outputs.region_snbt", WtemConfig.DEFAULT.outputs().exportRegionSnbt(), () -> draft.exportRegionSnbt, value -> draft.exportRegionSnbt = value));
        category.option(bool("gui.wtem.config.outputs.schema", WtemConfig.DEFAULT.outputs().exportSchema(), () -> draft.exportSchema, value -> draft.exportSchema = value));
        category.option(string("gui.wtem.config.outputs.region_directory", WtemConfig.DEFAULT.outputs().regionSnbtDirectory(), () -> draft.regionSnbtDirectory, value -> draft.regionSnbtDirectory = value));
        category.option(string("gui.wtem.config.outputs.schema_file", WtemConfig.DEFAULT.outputs().schemaFile(), () -> draft.schemaFile, value -> draft.schemaFile = value));
        category.option(string("gui.wtem.config.language_file", WtemConfig.DEFAULT.languageFile(), () -> draft.languageFile, value -> draft.languageFile = value));
        return category.build();
    }

    private static ConfigCategory aiTranslation(Draft draft) {
        ConfigCategory.Builder category = ConfigCategory.createBuilder().name(text("gui.wtem.config.ai"));
        category.option(bool("gui.wtem.config.ai.enabled", WtemConfig.DEFAULT.aiTranslation().enabled(), () -> draft.aiEnabled, value -> draft.aiEnabled = value));
        category.option(
                Option.<WtemConfig.AiTranslation.Protocol>createBuilder()
                        .name(text("gui.wtem.config.ai.protocol"))
                        .description(OptionDescription.of(text("gui.wtem.config.ai.protocol.description")))
                        .binding(WtemConfig.DEFAULT.aiTranslation().protocol(), () -> draft.aiProtocol, value -> draft.aiProtocol = value)
                        .controller(option -> EnumControllerBuilder.create(option).enumClass(WtemConfig.AiTranslation.Protocol.class))
                        .build());
        category.option(string("gui.wtem.config.ai.endpoint", WtemConfig.DEFAULT.aiTranslation().endpoint(), () -> draft.aiEndpoint, value -> draft.aiEndpoint = value));
        category.option(string("gui.wtem.config.ai.api_key", "", () -> draft.aiApiKey, value -> draft.aiApiKey = value));
        category.option(string("gui.wtem.config.ai.model", WtemConfig.DEFAULT.aiTranslation().model(), () -> draft.aiModel, value -> draft.aiModel = value));
        category.option(string("gui.wtem.config.ai.target_language", WtemConfig.DEFAULT.aiTranslation().targetLanguage(), () -> draft.aiTargetLanguage, value -> draft.aiTargetLanguage = value));
        category.option(string("gui.wtem.config.ai.output_file", WtemConfig.DEFAULT.aiTranslation().outputFile(), () -> draft.aiOutputFile, value -> draft.aiOutputFile = value));
        category.option(integer("gui.wtem.config.ai.timeout", WtemConfig.DEFAULT.aiTranslation().timeoutSeconds(), () -> draft.aiTimeout, value -> draft.aiTimeout = value, 1, 600));
        category.option(string("gui.wtem.config.ai.translation_prompt", WtemConfig.DEFAULT.aiTranslation().translationPrompt(), () -> draft.aiTranslationPrompt, value -> draft.aiTranslationPrompt = value));
        category.option(string("gui.wtem.config.ai.key_naming_prompt", WtemConfig.DEFAULT.aiTranslation().keyNamingPrompt(), () -> draft.aiKeyNamingPrompt, value -> draft.aiKeyNamingPrompt = value));
        return category.build();
    }

    private static ConfigCategory resourcePack(Draft draft) {
        ConfigCategory.Builder category = ConfigCategory.createBuilder().name(text("gui.wtem.config.resource_pack"));
        category.option(bool("gui.wtem.config.resource_pack.enabled", WtemConfig.DEFAULT.resourcePack().enabled(), () -> draft.packEnabled, value -> draft.packEnabled = value));
        category.option(
                Option.<WtemConfig.ResourcePack.Format>createBuilder()
                        .name(text("gui.wtem.config.resource_pack.format"))
                        .description(
                                OptionDescription.of(
                                        text("gui.wtem.config.resource_pack.format.description")))
                        .binding(WtemConfig.DEFAULT.resourcePack().format(), () -> draft.packFormat, value -> draft.packFormat = value)
                        .controller(option -> EnumControllerBuilder.create(option).enumClass(WtemConfig.ResourcePack.Format.class))
                        .build());
        category.option(string("gui.wtem.config.resource_pack.name", WtemConfig.DEFAULT.resourcePack().name(), () -> draft.packName, value -> draft.packName = value));
        category.option(string("gui.wtem.config.resource_pack.description", WtemConfig.DEFAULT.resourcePack().description(), () -> draft.packDescription, value -> draft.packDescription = value));
        category.option(integer("gui.wtem.config.resource_pack.pack_format", WtemConfig.DEFAULT.resourcePack().packFormat(), () -> draft.packPackFormat, value -> draft.packPackFormat = value, 0, 999));
        return category.build();
    }

    private static ConfigCategory advanced(Draft draft) {
        ConfigCategory.Builder category = ConfigCategory.createBuilder().name(text("gui.wtem.config.advanced"));
        category.option(bool("gui.wtem.config.skipped.command_output", WtemConfig.DEFAULT.skipped().commandBlockOutput(), () -> draft.commandBlockOutput, value -> draft.commandBlockOutput = value));
        category.option(bool("gui.wtem.config.skipped.filtered_text", WtemConfig.DEFAULT.skipped().filteredText(), () -> draft.filteredText, value -> draft.filteredText = value));
        category.group(list("gui.wtem.config.saved_data_text_fields", WtemConfig.DEFAULT.savedDataTextFields(), () -> draft.savedDataTextFields, values -> draft.savedDataTextFields = values));
        return category.build();
    }

    private static Option<Boolean> bool(String key, boolean defaultValue, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
                .name(text(key))
                .description(OptionDescription.of(text(key + ".description")))
                .binding(defaultValue, getter, setter)
                .controller(TickBoxControllerBuilder::create)
                .build();
    }

    private static Option<Integer> integer(String key, int defaultValue, Supplier<Integer> getter, Consumer<Integer> setter, int min, int max) {
        return Option.<Integer>createBuilder()
                .name(text(key))
                .description(OptionDescription.of(text(key + ".description")))
                .binding(defaultValue, getter, setter)
                .controller(option -> IntegerSliderControllerBuilder.create(option).range(min, max).step(1))
                .build();
    }

    private static Option<String> string(String key, String defaultValue, Supplier<String> getter, Consumer<String> setter) {
        return Option.<String>createBuilder()
                .name(text(key))
                .description(OptionDescription.of(text(key + ".description")))
                .binding(defaultValue, getter, setter)
                .controller(StringControllerBuilder::create)
                .build();
    }

    private static ListOption<String> list(
            String key,
            List<String> defaultValue,
            Supplier<List<String>> getter,
            Consumer<List<String>> setter) {
        return ListOption.<String>createBuilder()
                .name(text(key))
                .description(OptionDescription.of(text(key + ".description")))
                .binding(defaultValue, getter, setter)
                .controller(StringControllerBuilder::create)
                .initial("")
                .minimumNumberOfEntries(0)
                .maximumNumberOfEntries(128)
                .insertEntriesAtEnd(true)
                .build();
    }

    private static Component text(String key) {
        return Component.translatable(key);
    }

    private static List<String> mapLines(Map<String, ?> map) {
        List<String> result = new java.util.ArrayList<>();
        map.forEach(
                (key, value) -> result.add(escape(key) + "=" + escape(String.valueOf(value))));
        return List.copyOf(result);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static final class Draft {
        private final Map<WtemConfig.Stage, Boolean> stages = new LinkedHashMap<>();
        private final Map<String, Boolean> resources = new LinkedHashMap<>();
        private boolean keyReuseDefault;
        private List<String> keyReuseOverrides;
        private WtemConfig.KeyNaming.Scheme keyNamingScheme;
        private int randomLength;
        private List<String> builtinEntries;
        private int nbtMaxDepth;
        private boolean rebuildNestedKeys;
        private boolean commandBlockOutput;
        private boolean filteredText;
        private List<String> skippedPaths;
        private List<String> savedDataTextFields;
        private ExtractionPatterns patterns;
        private List<String> filtersRegion;
        private List<String> filtersDatapack;
        private List<String> filtersStorage;
        private List<String> filtersEntity;
        private List<String> filtersBlockEntity;
        private WtemConfig.Filters.Selection selection;
        private boolean exportRegionSnbt;
        private boolean exportSchema;
        private String regionSnbtDirectory;
        private String schemaFile;
        private String languageFile;
        private boolean aiEnabled;
        private WtemConfig.AiTranslation.Protocol aiProtocol;
        private String aiEndpoint;
        private String aiApiKey;
        private String aiModel;
        private String aiTargetLanguage;
        private String aiOutputFile;
        private int aiBatchSize;
        private int aiTimeout;
        private String aiTranslationPrompt;
        private String aiKeyNamingPrompt;
        private boolean packEnabled;
        private WtemConfig.ResourcePack.Format packFormat;
        private String packName;
        private String packDescription;
        private int packPackFormat;

        static Draft from(WtemConfig config) {
            Draft draft = new Draft();
            for (WtemConfig.Stage stage : WtemConfig.Stage.values()) draft.stages.put(stage, config.isEnabled(stage));
            draft.resources.putAll(config.resources());
            draft.keyReuseDefault = config.keyReuse().byDefault();
            draft.keyReuseOverrides = mapLines(config.keyReuse().overrides());
            draft.keyNamingScheme = config.keyNaming().scheme();
            draft.randomLength = config.keyNaming().randomLength();
            draft.builtinEntries = mapLines(config.builtinEntries());
            draft.nbtMaxDepth = config.nbtMaxDepth();
            draft.rebuildNestedKeys = config.rebuildNestedKeys();
            draft.commandBlockOutput = config.skipped().commandBlockOutput();
            draft.filteredText = config.skipped().filteredText();
            draft.skippedPaths = config.skippedPaths();
            draft.savedDataTextFields = config.savedDataTextFields();
            draft.patterns = config.patterns();
            draft.filtersRegion = config.filters().region();
            draft.filtersDatapack = config.filters().datapack();
            draft.filtersStorage = config.filters().storage();
            draft.filtersEntity = config.filters().entity();
            draft.filtersBlockEntity = config.filters().blockEntity();
            draft.selection = config.filters().selection();
            draft.exportRegionSnbt = config.outputs().exportRegionSnbt();
            draft.exportSchema = config.outputs().exportSchema();
            draft.regionSnbtDirectory = config.outputs().regionSnbtDirectory();
            draft.schemaFile = config.outputs().schemaFile();
            draft.languageFile = config.languageFile();
            draft.aiEnabled = config.aiTranslation().enabled();
            draft.aiProtocol = config.aiTranslation().protocol();
            draft.aiEndpoint = config.aiTranslation().endpoint();
            draft.aiApiKey = config.aiTranslation().apiKey();
            draft.aiModel = config.aiTranslation().model();
            draft.aiTargetLanguage = config.aiTranslation().targetLanguage();
            draft.aiOutputFile = config.aiTranslation().outputFile();
            draft.aiBatchSize = config.aiTranslation().batchSize();
            draft.aiTimeout = config.aiTranslation().timeoutSeconds();
            draft.aiTranslationPrompt = config.aiTranslation().translationPrompt();
            draft.aiKeyNamingPrompt = config.aiTranslation().keyNamingPrompt();
            draft.packEnabled = config.resourcePack().enabled();
            draft.packFormat = config.resourcePack().format();
            draft.packName = config.resourcePack().name();
            draft.packDescription = config.resourcePack().description();
            draft.packPackFormat = config.resourcePack().packFormat();
            return draft;
        }

        WtemConfig toConfig() {
            // Parse the map-entry lists at the save boundary.  Malformed rows are ignored by the
            // same validation rules as the JSON loader.
            Map<String, Boolean> overrides = parseBooleanMap(keyReuseOverrides);
            Map<String, String> builtins = parseStringMap(builtinEntries);
            return new WtemConfig(
                    stages,
                    resources,
                    new WtemConfig.KeyReuse(keyReuseDefault, overrides),
                    new WtemConfig.KeyNaming(keyNamingScheme, randomLength),
                    nbtMaxDepth,
                    rebuildNestedKeys,
                    new WtemConfig.Skipped(commandBlockOutput, filteredText),
                    skippedPaths,
                    builtins,
                    languageFile,
                    new WtemConfig.Filters(
                            filtersRegion,
                            filtersDatapack,
                            filtersStorage,
                            filtersEntity,
                            filtersBlockEntity,
                            configSelection()),
                    new WtemConfig.Outputs(exportRegionSnbt, exportSchema, regionSnbtDirectory, schemaFile),
                    new WtemConfig.AiTranslation(
                            aiEnabled,
                            aiEndpoint,
                            aiApiKey,
                            aiModel,
                            aiTargetLanguage,
                            aiOutputFile,
                            aiBatchSize,
                            aiTimeout,
                            aiTranslationPrompt,
                            aiKeyNamingPrompt,
                            aiProtocol),
                    new WtemConfig.ResourcePack(packEnabled, packFormat, packName, packDescription, packPackFormat),
                    savedDataTextFields,
                    patterns);
        }

        private WtemConfig.Filters.Selection configSelection() {
            return this.selection == null
                    ? WtemConfig.Filters.Selection.DEFAULT
                    : this.selection;
        }

        private static Map<String, Boolean> parseBooleanMap(List<String> lines) {
            Map<String, Boolean> result = new LinkedHashMap<>();
            if (lines == null) return result;
            for (String line : lines) {
                int separator = line.indexOf('=');
                if (separator <= 0) continue;
                String key = unescape(line.substring(0, separator)).trim();
                String value = unescape(line.substring(separator + 1)).trim();
                if (!key.isBlank() && ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) result.put(key, Boolean.parseBoolean(value));
            }
            return result;
        }

        private static Map<String, String> parseStringMap(List<String> lines) {
            Map<String, String> result = new LinkedHashMap<>();
            if (lines == null) return result;
            for (String line : lines) {
                int separator = line.indexOf('=');
                if (separator <= 0) continue;
                String key = unescape(line.substring(0, separator)).trim();
                if (!key.isBlank()) result.put(key, unescape(line.substring(separator + 1)));
            }
            return result;
        }

        private static String unescape(String value) {
            StringBuilder result = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                if (character != '\\' || i + 1 >= value.length()) {
                    result.append(character);
                    continue;
                }

                char escaped = value.charAt(++i);
                switch (escaped) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case '\\' -> result.append('\\');
                    default -> result.append('\\').append(escaped);
                }
            }
            return result.toString();
        }
    }

}
