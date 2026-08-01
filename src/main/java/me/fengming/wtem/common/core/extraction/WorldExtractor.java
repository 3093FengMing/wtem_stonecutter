package me.fengming.wtem.common.core.extraction;

import com.mojang.datafixers.DataFixer;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.config.WtemConfig;
import me.fengming.wtem.common.core.extraction.service.ExtractionProgress;
import me.fengming.wtem.common.core.extraction.service.ExtractionReport;
import me.fengming.wtem.common.core.extraction.service.ExtractionSession;
import me.fengming.wtem.common.core.extraction.service.ExtractionStatus;
import me.fengming.wtem.common.core.extraction.table.ExtractionManifest;
import me.fengming.wtem.common.core.handler.AbstractWHandler;
import me.fengming.wtem.common.core.handler.BlockEntityWHandler;
import me.fengming.wtem.common.core.handler.EntityWHandler;
import me.fengming.wtem.common.core.handler.datapack.ResourceHandler;
import me.fengming.wtem.common.core.handler.datapack.ResourceHandlers;
import me.fengming.wtem.common.core.handler.datapack.command.FunctionHandler;
import me.fengming.wtem.common.util.ChangeTracker;
import me.fengming.wtem.common.util.DirectoryPublisher;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.ResourceIo;
import me.fengming.wtem.common.util.ResourceIds;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.util.worldupdate.WorldUpgrader;
//? if >=1.21.5
import net.minecraft.world.scores.ScoreboardSaveData;
//? if >=26.1 {
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.util.worldupdate.RegionStorageUpgrader;
import net.minecraft.util.worldupdate.UpgradeProgress;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.SavedDataStorage;
import java.util.ArrayList;
import java.util.concurrent.ThreadFactory;
//?} else {
/*import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.WorldData;

*///?}
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.NotNull;

/**
 * @author FengMing
 */
public class WorldExtractor extends WorldUpgrader implements AutoCloseable {
    private final ExtractionSession session = new ExtractionSession();
    private final AtomicBoolean closed = new AtomicBoolean();

    //? if >=26.1 {

    private static final ThreadFactory THREAD_FACTORY = new ThreadFactoryBuilder().setDaemon(true).build();

    private final List<ResourceKey<Level>> orderedLevels;
    private final SavedDataStorage savedDataStorage;

    private final Thread thread;

    //?} else {
    /*public static final MutableComponent STATUS_EXTRACTING = Component.translatable("gui.wtem.main.extraction.working");
    public static final MutableComponent STATUS_FINISHED_EXTRACTION = Component.translatable("gui.wtem.main.extraction.finished");
    *///?}

    private final DataFixer dataFixer;
    private final WorldStem worldStem;
    private final LevelStorageSource.LevelStorageAccess levelStorage;
    private final RegistryAccess registry;
    private final StructureTemplateManager structureManager;
    private final WtemConfig config = WtemConfig.active();

    public WorldExtractor(DataFixer dataFixer,
                          WorldStem worldStem,
                          LevelStorageSource.LevelStorageAccess levelStorage,
                          RegistryAccess registry
    ) {
        super(levelStorage, dataFixer,
                //? if >=1.21.5 <26.1
                //worldStem.worldData(),
                registry, false, false);
        this.dataFixer = dataFixer;
        this.worldStem = worldStem;
        this.levelStorage = levelStorage;
        this.registry = registry;
        var holderGetter = registry.lookupOrThrow(Registries.BLOCK).filterFeatures(
                //$if >=26.1 'worldStem.worldDataAndGenSettings().data().enabledFeatures()' else 'worldStem.worldData().enabledFeatures()'
                worldStem.worldDataAndGenSettings().data().enabledFeatures()
        );
        this.structureManager = new StructureTemplateManager(worldStem.resourceManager(), levelStorage, dataFixer, holderGetter);
        //? if >= 26.1 {

        var dimensions = registry.lookupOrThrow(Registries.LEVEL_STEM);
        this.orderedLevels =
                dimensions.registryKeySet().stream()
                        .map(Registries::levelStemToLevel)
                        .sorted(Comparator.comparing(ResourceIds::key))
                        .toList();

        this.savedDataStorage =
                new SavedDataStorage(
                        levelStorage.getLevelPath(LevelResource.DATA), dataFixer, registry);

        this.thread = THREAD_FACTORY.newThread(this::runExtractionSafely);

        //?}
    }

    public boolean startThread() {
        if (!this.session.start()) return false;

        try {
            this.thread.start();
            return true;
        } catch (RuntimeException exception) {
            this.session.fail(exception);
            close();
            throw exception;
        }
    }

    public ExtractionStatus getExtractionStatus() {
        return this.session.status();
    }

    public ExtractionReport getReport() {
        return this.session.report();
    }

    public ExtractionProgress getExtractionProgress() {
        //? if >=26.1 {
        List<ExtractionProgress.DimensionProgress> dimensions =
                this.orderedLevels.stream()
                        .map(level ->
                                new ExtractionProgress.DimensionProgress(
                                        level, this.upgradeProgress.getDimensionProgress(level)))
                        .toList();
        return new ExtractionProgress(
                this.upgradeProgress.getTotalChunks(),
                this.upgradeProgress.getConverted(),
                this.upgradeProgress.getSkipped(),
                this.upgradeProgress.getTotalProgress(),
                dimensions);
        //?} else {
        /*List<ExtractionProgress.DimensionProgress> dimensions =
                this.levels().stream()
                        .sorted(Comparator.comparing(ResourceIds::key))
                        .map(level ->
                                new ExtractionProgress.DimensionProgress(
                                        level, this.dimensionProgress(level)))
                        .toList();
        return new ExtractionProgress(
                this.getTotalChunks(),
                this.getConverted(),
                this.getSkipped(),
                this.getProgress(),
                dimensions);
        *///?}
    }

    @Override
    public void cancel() {
        ExtractionSession.CancellationResult result = this.session.requestCancellation();
        if (result == ExtractionSession.CancellationResult.IGNORED) return;

        //? if >=26.1 {
        this.upgradeProgress.setCanceled();
        //?} else {
        /*this.running = false;
        *///?}
        if (result == ExtractionSession.CancellationResult.CANCELLED_BEFORE_START) close();
    }

    public void extractDatapacks() {
        final var datapackDir = this.levelStorage.getLevelPath(LevelResource.DATAPACK_DIR);
        final ResourceHandler.Context context =
                ResourceHandler.Context.of(
                        null,
                        this.structureManager,
                        this.registry,
                        this.session);

        List<PackResources> packs =
                this.worldStem.resourceManager().listPacks()
                        .filter(pack -> pack.location().source() == PackSource.WORLD)
                        .sorted(Comparator.comparing(PackResources::packId))
                        .toList();
        for (PackResources pack : packs) {
            if (shouldStop()) return;
            String packId = pack.packId();
            if (isGeneratedCompanionPack(packId)) continue;

            String outputPackId = sanitizePackId(packId) + "_" + shortHash(packId) + "_wtem";
            Path outputRoot = datapackDir.resolve(outputPackId);
            Path staging = null;
            try (var transaction = TranslationContext.beginTransaction();
                    var ignored = TranslationContext.pushLocation(packId)) {
                staging = DirectoryPublisher.createStagingDirectory(outputRoot);
                Path stagingRoot = staging;
                int modifiedResources =
                        extractPackMetadata(
                                        pack,
                                        stagingRoot.resolve("pack.mcmeta"),
                                        outputPackId)
                                ? 1
                                : 0;

                Function<Identifier, Path> filePath =
                        rl ->
                                stagingRoot.resolve(
                                        "data/"
                                                + rl.getNamespace()
                                                + "/"
                                                + rl.getPath());
                for (String namespace : pack.getNamespaces(PackType.SERVER_DATA).stream().sorted().toList()) {
                    if (shouldStop()) break;
                    for (var factory : ResourceHandlers.all()) {
                        if (shouldStop()) break;
                        var handler = factory.newHandler(filePath, context);
                        if (!this.config.isResourceEnabled(handler.getPath())) continue;
                        Map<String, PackResource> resources = new TreeMap<>();
                        pack.listResources(
                                PackType.SERVER_DATA,
                                namespace,
                                handler.getPath(),
                                (id, supplier) ->
                                        {
                                            if (handler.accepts(id)
                                                    && !this.config.isPathSkipped(id.getPath())) {
                                                resources.put(
                                                        id.toString(),
                                                        new PackResource(id, supplier));
                                            }
                                        });
                        for (PackResource resource : resources.values()) {
                            if (shouldStop()) break;
                            if (handler.handle(resource.id(), resource.supplier())) {
                                modifiedResources++;
                            }
                        }
                    }
                }

                if (shouldStop()) continue;
                boolean replacingPreviousOutput = Files.exists(outputRoot);
                if (modifiedResources == 0 && !replacingPreviousOutput) continue;

                DirectoryPublisher.publish(staging, outputRoot);
                staging = null;
                transaction.commit();
                this.session.recordModifiedResources(
                        Math.max(modifiedResources, replacingPreviousOutput ? 1 : 0));
            } catch (RuntimeException exception) {
                this.session.diagnostics().record("datapack", packId, exception);
                Wtem.LOGGER.error("Failed to extract data pack {}", packId, exception);
            } finally {
                if (staging != null) {
                    try {
                        DirectoryPublisher.discard(staging);
                    } catch (RuntimeException exception) {
                        this.session.diagnostics().record(
                                "datapack_staging", packId, exception);
                        Wtem.LOGGER.warn(
                                "Failed to remove staging directory for data pack {}",
                                packId,
                                exception);
                    }
                }
            }
        }
    }

    private static String sanitizePackId(String packId) {
        String sanitized = packId.replaceAll("[^A-Za-z0-9._-]", "_");
        sanitized = sanitized.replaceAll("^\\.+|\\.+$", "_");
        if (sanitized.length() > 64) sanitized = sanitized.substring(0, 64);
        return sanitized.isBlank() ? "pack" : sanitized;
    }

    private static boolean isGeneratedCompanionPack(String packId) {
        return packId.matches(".*_[0-9a-f]{8}_wtem");
    }

    private static String shortHash(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static boolean extractPackMetadata(PackResources pack, Path output, String packId) {
        var supplier = pack.getRootResource("pack.mcmeta");
        if (supplier == null) {
            throw new IllegalStateException("Data pack has no pack.mcmeta: " + pack.packId());
        }

        var metadata = ResourceIo.readJson(supplier, "");
        if (!metadata.isJsonObject()) {
            throw new IllegalStateException("Data pack metadata is not a JSON object: " + pack.packId());
        }
        TranslationContext.setKey("datapack." + packId.replace('.', '_'));
        boolean changed =
                TranslationUtils.translateJsonElement(
                        metadata.getAsJsonObject(), "pack.description");
        ResourceIo.writeJson(output, metadata);
        return changed;
    }

    private record PackResource(Identifier id, IoSupplier<InputStream> supplier) {}

    public void extractStructures() {
        String structureDirectory =
                //? if >=26.1 {
                "structure";
                //?} else
                //"structures";
        new GeneratedStructureExtractor(
                        this.levelStorage.getLevelPath(LevelResource.GENERATED_DIR),
                        structureDirectory,
                        this.session)
                .extract();
    }

    public void extractBossBar() {
        //? if >=26.1 {
        CustomBossEvents events = this.savedDataStorage.computeIfAbsent(CustomBossEvents.TYPE);
        ChangeTracker tracker = new ChangeTracker();
        for (var event : events.getEvents()) {
            // A boss bar has no position, so its own id is the only thing that identifies it, and
            // it is what the /bossbar command takes as an argument.
            try (var ignored = TranslationContext.pushSubject(event.customId().toString())) {
                var original = event.getName();
                var translated = TranslationUtils.translateLiteral(original);
                if (!tracker.add(translated != original)) continue;
                event.setName(translated);
            }
        }
        if (tracker.isChanged()) {
            events.setDirty();
            this.session.recordModifiedSavedData();
        }
        //?} else {
        /*WorldData worldData = this.worldStem.worldData();
        CompoundTag bossBarTag = worldData.getCustomBossEvents();
        if (bossBarTag == null) return;
        ChangeTracker tracker = new ChangeTracker();
        for (String key : NbtUtils.getKeys(bossBarTag)) {
            // The map key is the bar id, which is what the /bossbar command takes as an argument.
            try (var ignored = TranslationContext.pushSubject(key)) {
                tracker.add(
                        TranslationUtils.translateNbtComponent(
                                NbtUtils.getCompound(bossBarTag, key), "Name"));
            }
        }
        if (!tracker.isChanged()) return;
        worldData.setCustomBossEvents(bossBarTag);
        this.levelStorage.saveDataTag(this.registry, worldData, null);
        this.session.recordModifiedSavedData();
        *///?}
    }

    public void extractScoreBoard() {
        CustomScoreBoard sb = new CustomScoreBoard();
        //? if >=26.1 {
        sb.load(this.savedDataStorage.computeIfAbsent(ScoreboardSaveData.TYPE).getData());
        if (sb.extract()) {
            this.savedDataStorage.set(ScoreboardSaveData.TYPE, new ScoreboardSaveData(sb.store()));
            this.session.recordModifiedSavedData();
        }
        //?} else if >=1.21.11 {
        /*ScoreboardSaveData data = this.overworldDataStorage.computeIfAbsent(ScoreboardSaveData.TYPE);
        sb.load(data.getData());
        if (sb.extract()) {
            data.setData(sb.store());
            this.session.recordModifiedSavedData();
        }
        *///?} else if >=1.21.5 {
        /*// Reading through the descriptor populates `sb` as a side effect, because the save data
        // is a view over the scoreboard rather than a separate snapshot.
        ScoreboardSaveData data = this.overworldDataStorage.computeIfAbsent(sb.dataType());
        if (sb.extract()) {
            data.setDirty();
            this.session.recordModifiedSavedData();
        }
        *///?} else {
        /*this.overworldDataStorage.get(sb.dataFactory(), "scoreboard");
        if (sb.extract()) this.session.recordModifiedSavedData();
        *///?}
    }

    //? if >=26.1 {
    private void runExtractionSafely() {
        beginRun();
        try {
            FunctionHandler.initializeParser(this.registry, this.session.diagnostics());
            runExtraction();
            finishRun();
        } catch (Throwable throwable) {
            handleFatalFailure(throwable);
        } finally {
            this.upgradeProgress.setFinished(true);
            try {
                closeWorkerResources();
            } finally {
                FunctionHandler.releaseParser();
                TranslationContext.release();
            }
        }
    }

    private void runExtraction() {
        runStage(
                WtemConfig.Stage.REGION,
                () -> work(DataFixTypes.CHUNK, new BlockEntityWHandler(), "region"));
        runStage(
                WtemConfig.Stage.ENTITIES,
                () -> work(DataFixTypes.ENTITY_CHUNK, new EntityWHandler(), "entities"));
        if (shouldStop()) return;

        runNonRegionExtraction();

        this.savedDataStorage.saveAndJoin();
    }

    private void work(DataFixTypes dataFixType, AbstractWHandler<CompoundTag> handler, String folderName) {
        List<RegionStorageUpgrader> upgraders = new ArrayList<>();
        this.upgradeProgress.reset(dataFixType);
        this.upgradeProgress.setType(UpgradeProgress.Type.REGIONS);
        int previousCopiesFileAmounts = 0;

        for (ResourceKey<Level> level : this.orderedLevels) {
            var upgrader = new ChunkExtractor(handler, dataFixType, folderName, previousCopiesFileAmounts);
            upgrader.init(level, this.levelStorage);
            previousCopiesFileAmounts += upgrader.fileAmount();
            upgraders.add(upgrader);
        }

        for (var upgrader : upgraders) {
            if (shouldStop()) return;
            upgrader.upgrade();
        }
    }
    //?} else {

    /*@Override
    public void work() {
        beginRun();
        try {
            FunctionHandler.initializeParser(this.registry, this.session.diagnostics());
            runStage(
                    WtemConfig.Stage.REGION,
                    () ->
                            new ChunkExtractor(
                                            new BlockEntityWHandler(),
                                            DataFixTypes.CHUNK,
                                            "region")
                                    .upgrade());
            runStage(
                    WtemConfig.Stage.ENTITIES,
                    () ->
                            new ChunkExtractor(
                                            new EntityWHandler(),
                                            DataFixTypes.ENTITY_CHUNK,
                                            "entities")
                                    .upgrade());
            if (!shouldStop()) runNonRegionExtraction();
            if (!shouldStop()) saveLegacySavedData();
            finishRun();
        } catch (Throwable throwable) {
            handleFatalFailure(throwable);
        } finally {
            this.finished = true;
            try {
                closeWorkerResources();
            } finally {
                FunctionHandler.releaseParser();
                TranslationContext.release();
            }
        }
    }
    *///?}

    //? if <26.1 {
    /*private void saveLegacySavedData() {
        //? if =1.21.1 {
        /^this.overworldDataStorage.save();
        ^///?} else
        this.overworldDataStorage.saveAndJoin();
    }
    *///?}

    private void runNonRegionExtraction() {
        runStage(WtemConfig.Stage.SCOREBOARD, this::extractScoreBoard);
        runStage(WtemConfig.Stage.BOSS_BAR, this::extractBossBar);
        runStage(WtemConfig.Stage.DATAPACKS, this::extractDatapacks);
        runStage(WtemConfig.Stage.GENERATED_STRUCTURES, this::extractStructures);
    }

    private void runStage(WtemConfig.Stage stage, Runnable extraction) {
        if (shouldStop() || !this.config.isEnabled(stage)) return;
        // Every row in the report is attributed to the stage that produced it, so a translator
        // reading the file knows whether to look in a chunk, a data pack, or the scoreboard.
        try (var ignored = TranslationContext.pushSource(stage.id())) {
            extraction.run();
        }
    }

    private void beginRun() {
        TranslationContext.clear();
        TranslationContext.setKeyReuse(this.config.keyReuse());
        TranslationContext.setKeyNaming(this.config.keyNaming());
        TranslationContext.setBuiltinEntries(this.config.builtinEntries());
    }

    private boolean shouldStop() {
        return this.session.isCancellationRequested();
    }

    private void finishRun() {
        this.session.setTranslatedEntries(TranslationContext.extractedEntryCount());
        if (shouldStop()) {
            publishPartialLanguage();
            this.session.completeCancellation();
            logDiagnostics();
            return;
        }

        exportLanguage(languageOutput());
        exportManifest();
        this.session.complete();
        logDiagnostics();
    }

    private void handleFatalFailure(Throwable throwable) {
        this.session.setTranslatedEntries(TranslationContext.extractedEntryCount());
        this.session.fail(throwable);
        publishPartialLanguage();
        logDiagnostics();
        Wtem.LOGGER.error("Failed to extract world", throwable);
    }

    private void logDiagnostics() {
        for (ExtractionDiagnostics.Failure failure : this.session.diagnostics().failures()) {
            Wtem.LOGGER.warn(
                    "Extraction warning [{}] {}",
                    failure.scope(),
                    failure.resource(),
                    failure.cause());
        }
    }

    private void publishPartialLanguage() {
        if (TranslationContext.extractedEntryCount() == 0) return;
        try {
            exportLanguage(languageOutput());
        } catch (RuntimeException exception) {
            this.session.diagnostics().record("language", languageOutput().toString(), exception);
            Wtem.LOGGER.error("Failed to publish partial language catalog", exception);
        }
        exportManifest();
    }

    private void exportManifest() {
        Path file = manifestOutput();
        try {
            ResourceIo.writeString(
                    file, ExtractionManifest.render(TranslationContext.records()));
        } catch (RuntimeException exception) {
            this.session.diagnostics().record("manifest", file.toString(), exception);
            Wtem.LOGGER.error("Failed to write the extraction report", exception);
        }
    }

    private Path languageOutput() {
        return levelRoot().resolve(this.config.languageFile());
    }

    private Path manifestOutput() {
        return levelRoot().resolve(ExtractionManifest.fileName(this.config.languageFile()));
    }

    private Path levelRoot() {
        return this.levelStorage.getLevelPath(LevelResource.ROOT);
    }

    public static void exportLanguage(Path file) {
        ResourceIo.writeString(file, TranslationContext.exportLanguage());
    }

    private void closeWorkerResources() {
        try {
            close();
        } catch (Throwable closeFailure) {
            if (this.session.status().isSuccessful()) {
                this.session.fail(closeFailure);
            } else {
                this.session.diagnostics().record(
                        "close", this.levelStorage.getLevelPath(LevelResource.ROOT).toString(), closeFailure);
            }
            Wtem.LOGGER.error("Failed to close world extraction resources", closeFailure);
        }
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) return;
        try {
            //? if >=26.1 {
            // Closing flushes, so this has to happen even on a failed run: the level-root storage is
            // ours rather than the upgrader's, and super.close() does not know about it.
            this.savedDataStorage.close();
            //?}
            //? if >1.21.1 {
            super.close();
            //?} else {
            /*this.overworldDataStorage.save();
            *///?}
        } finally {
            this.worldStem.close();
        }
    }

    class ChunkExtractor extends RegionStorageUpgrader {
        private final AbstractWHandler<CompoundTag> handler;

        protected ChunkExtractor(AbstractWHandler<CompoundTag> handler, DataFixTypes type, String folderName/*?if >=26.1 >>')'*/, int previousCopiesFileAmounts) {
            //? if >= 26.1 {
            super(WorldExtractor.this.dataFixer, type, folderName, folderName, -1, false, WorldExtractor.this.upgradeProgress, previousCopiesFileAmounts, null, Int2ObjectMap.ofEntries());
            //?} else {
            /*super(type, folderName, STATUS_EXTRACTING, STATUS_FINISHED_EXTRACTION);
            *///?}
            this.handler = handler;
        }

        @Override
        protected boolean tryProcessOnePosition(SimpleRegionStorage storage, @NotNull ChunkPos chunkPos/*?if <26.1 >>')'*//*, ResourceKey<Level> resourceKey*/) {
            CompoundTag compoundTag = storage.read(chunkPos).join().orElse(null);
            if (compoundTag == null) return false;
            return process(storage, chunkPos, compoundTag);
        }

        @Override
        protected @NotNull CompoundTag upgradeTag(@NotNull SimpleRegionStorage storage, @NotNull CompoundTag compoundTag/*?if >= 26.1 >>')'*/, int targetVersion) {
            return compoundTag;
        }

        private boolean process(SimpleRegionStorage storage, ChunkPos chunkPos, CompoundTag compoundTag) {
            ChangeTracker tracker = new ChangeTracker();
            ListTag list = NbtUtils.getList(compoundTag, handler.getName(), Tag.TAG_COMPOUND);
            // The storage knows its own dimension on every supported version, unlike the upgrader,
            // whose dimension key is private. Block entities carry absolute coordinates already, so
            // the chunk is only recorded to point at the region file the data lives in. ChunkPos
            // became a record in 26.1, so its own toString is used instead of its coordinates: it
            // renders '[x, z]' on every supported version.
            try (var ignored =
                    TranslationContext.pushLocation(
                            ResourceIds.key(storage.storageInfo().dimension())
                                    + " chunk "
                                    + chunkPos)) {
                for (int i = 0; i < list.size(); i++) {
                    tracker.add(this.handler.handle(NbtUtils.getCompound(list, i)));
                }
            }
            if (!tracker.isChanged()) return false;
            if (this.previousWriteFuture != null) this.previousWriteFuture.join();
            this.previousWriteFuture = storage.write(chunkPos, /*?if >=26.1 >>'compoundTag'*/() -> compoundTag);
            WorldExtractor.this.session.recordModifiedChunk();
            return true;
        }
    }
}
