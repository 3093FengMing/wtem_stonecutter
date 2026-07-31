package me.fengming.wtem.common.core;

import com.mojang.datafixers.DataFixer;
import me.fengming.wtem.common.Wtem;
import me.fengming.wtem.common.core.handler.AbstractWHandler;
import me.fengming.wtem.common.core.handler.BlockEntityWHandler;
import me.fengming.wtem.common.core.handler.EntityWHandler;
import me.fengming.wtem.common.core.handler.StructureTemplateWHandler;
import me.fengming.wtem.common.core.handler.datapack.ResourceHandler;
import me.fengming.wtem.common.core.handler.datapack.ResourceHandlers;
import me.fengming.wtem.common.core.misc.CustomScoreBoard;
import me.fengming.wtem.common.util.NbtUtils;
import me.fengming.wtem.common.util.ResourceIo;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.client.Minecraft;
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
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.util.worldupdate.WorldUpgrader;
//? if >=1.21.11
import net.minecraft.world.scores.ScoreboardSaveData;
//? if >=26.1 {
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplatePathFactory;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.util.worldupdate.RegionStorageUpgrader;
import net.minecraft.util.worldupdate.UpgradeProgress;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.SavedDataStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;
//?} else {
/*import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.WorldData;

*///?}
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;

/**
 * @author FengMing
 */
public class WorldExtractor extends WorldUpgrader {
    //? if >=26.1 {

    private static final ThreadFactory THREAD_FACTORY = new ThreadFactoryBuilder().setDaemon(true).build();

    private final SavedDataStorage savedDataStorage;
    private final Set<ResourceKey<@NotNull Level>> levels;

    private final Thread thread;
    private final UpgradeProgress progress = new UpgradeProgress();

    //?} else {
    /*public static final MutableComponent STATUS_EXTRACTING = Component.translatable("gui.wtem.main.extraction.working");
    public static final MutableComponent STATUS_FINISHED_EXTRACTION = Component.translatable("gui.wtem.main.extraction.finished");
    *///?}

    private final DataFixer dataFixer;
    private final WorldStem worldStem;
    private final LevelStorageSource.LevelStorageAccess levelStorage;
    private final RegistryAccess registry;
    private final StructureTemplateManager structureManager;

    public WorldExtractor(Minecraft mc,
                          DataFixer dataFixer,
                          WorldStem worldStem,
                          LevelStorageSource.LevelStorageAccess levelStorage,
                          RegistryAccess registry
    ) {
        super(levelStorage, dataFixer,
                //? if >=1.21.11 <26.1
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

        this.savedDataStorage = new SavedDataStorage(levelStorage.getLevelPath(LevelResource.DATA), dataFixer, registry);
        var dimensions = registry.lookupOrThrow(Registries.LEVEL_STEM);
        this.levels = dimensions.registryKeySet().stream().map(Registries::levelStemToLevel).collect(Collectors.toUnmodifiableSet());

        this.thread = THREAD_FACTORY.newThread(this::work);
        this.thread.setUncaughtExceptionHandler((t, e) -> {
            Wtem.LOGGER.error("Error upgrading world", e);
            this.progress.setFinished(true);
        });

        //?}
    }

    //? if >=26.1 {
    public float getProgress() {
        return this.progress.getTotalProgress();
    }
    //?}

    public void startThread() {
        this.thread.start();
    }

    public void extractDatapacks() {
        final var datapackDir = this.levelStorage.getLevelPath(LevelResource.DATAPACK_DIR);
        final ResourceHandler.Context context = ResourceHandler.Context.of(null, this.structureManager);

        for (PackResources pack : this.worldStem.resourceManager().listPacks().toList()) {
            String packId = pack.packId();
            if ("vanilla".equals(packId) || "fabric".equals(packId)) continue;

            int separator = Math.max(packId.lastIndexOf('/'), packId.lastIndexOf('\\'));
            String outputPackId = separator < 0 ? packId : packId.substring(separator + 1);
            Path outputRoot = datapackDir.resolve(outputPackId + "_wtem");
            extractPackMetadata(pack, outputRoot.resolve("pack.mcmeta"), outputPackId);

            Function<Identifier, Path> filePath =
                    rl ->
                            outputRoot.resolve(
                                    "data/"
                                            + rl.getNamespace()
                                            + "/"
                                            + rl.getPath());
            for (String namespace : pack.getNamespaces(PackType.SERVER_DATA)) {
                ResourceHandlers.getStream()
                        .forEachOrdered(
                                factory -> {
                                    var handler = factory.newHandler(filePath, context);
                                    pack.listResources(
                                            PackType.SERVER_DATA,
                                            namespace,
                                            handler.getPath(),
                                            handler::handle);
                                });
            }
        }
    }

    private static void extractPackMetadata(PackResources pack, Path output, String packId) {
        var supplier = pack.getRootResource("pack.mcmeta");
        if (supplier == null) return;

        var metadata = ResourceIo.readJson(supplier, "");
        if (!metadata.isJsonObject()) return;
        TranslationContext.setKey("datapack." + packId.replace('.', '_') + ".pack");
        TranslationUtils.translateJsonElement(metadata.getAsJsonObject(), "pack.description");
        ResourceIo.writeJson(output, metadata);
    }

    public void extractStructures() {
        //? if >=26.1
        var templates = new TemplatePathFactory(this.levelStorage.getLevelPath(LevelResource.GENERATED_DIR).normalize());
        this.structureManager.listTemplates().forEach(rl -> {
            var optional = this.structureManager.get(rl);
            if (optional.isEmpty()) return;
            CompoundTag modified = new StructureTemplateWHandler().handle(optional.get());
            //? if >=26.1 {
            Path filePath = templates.createAndValidatePathToStructure(rl);
            //?} else {
            /*Path filePath = this.structureManager.createAndValidatePathToGeneratedStructure(rl, ".nbt");
            *///?}
            ResourceIo.writeNbt(filePath, modified);
        });
    }

    public void extractBossBar() {
        //? if >=26.1 {
        CustomBossEvents events = this.savedDataStorage.computeIfAbsent(CustomBossEvents.TYPE);
        events.getEvents().forEach(event ->
                event.setName(TranslationUtils.translateLiteral(event.getName())));
        events.setDirty();
        //?} else {
        /*WorldData worldData = this.worldStem.worldData();
        CompoundTag bossBarTag = worldData.getCustomBossEvents();
        if (bossBarTag == null) return;
        NbtUtils.getKeys(bossBarTag).forEach(key ->
                TranslationUtils.translateNbtString(NbtUtils.getCompound(bossBarTag, key), "Name"));
        worldData.setCustomBossEvents(bossBarTag);
        this.levelStorage.saveDataTag(this.registry, worldData, null);
        *///?}
    }

    public void extractScoreBoard() {
        CustomScoreBoard sb = new CustomScoreBoard();
        //? if >=26.1 {
        sb.load(this.savedDataStorage.computeIfAbsent(ScoreboardSaveData.TYPE).getData());
        sb.extract();
        this.savedDataStorage.set(ScoreboardSaveData.TYPE, new ScoreboardSaveData(sb.store()));
        //?} else if >=1.21.11 {
        /*ScoreboardSaveData data = this.overworldDataStorage.computeIfAbsent(ScoreboardSaveData.TYPE);
        sb.load(data.getData());
        sb.extract();
        data.setData(sb.store());
        *///?} else {
        /*this.overworldDataStorage.get(sb.dataFactory(), "scoreboard");
        sb.extract();
        *///?}
    }

    //? if >=26.1 {
    private void work() {
        work(DataFixTypes.CHUNK, new BlockEntityWHandler(), "region");
        work(DataFixTypes.ENTITY_CHUNK, new EntityWHandler(), "entities");

        extractScoreBoard();
        extractBossBar();
        extractDatapacks();
        extractStructures();

        savedDataStorage.saveAndJoin();
        this.progress.setFinished(true);
    }

    private void work(DataFixTypes dataFixType, AbstractWHandler<CompoundTag> handler, String folderName) {
        List<RegionStorageUpgrader> upgraders = new ArrayList<>();
        this.progress.reset(dataFixType);
        this.progress.setType(UpgradeProgress.Type.REGIONS);
        int previousCopiesFileAmounts = 0;

        for (ResourceKey<Level> level : this.levels) {
            RegionStorageUpgrader upgrader = new ChunkExtractor(handler, dataFixType, folderName, previousCopiesFileAmounts);
            upgrader.init(level, this.levelStorage);
            previousCopiesFileAmounts += upgrader.fileAmount();
            upgraders.add(upgrader);
        }

        upgraders.forEach(RegionStorageUpgrader::upgrade);
    }
    //?} else {

    /*@Override
    public void work() {
        TranslationContext.clear();

        new ChunkExtractor(new BlockEntityWHandler(), DataFixTypes.CHUNK, "region").upgrade();
        new ChunkExtractor(new EntityWHandler(), DataFixTypes.ENTITY_CHUNK, "entities").upgrade();

        extractScoreBoard();
        extractBossBar();
        extractDatapacks();
        extractStructures();
        exportLanguage(this.levelStorage.getLevelPath(LevelResource.ROOT).resolve("en_us.json"));

        this.finished = true;
    }
    *///?}

    public static void exportLanguage(Path file) {
        try {
            Files.writeString(file, TranslationContext.exportLanguage());
        } catch (IOException e) {
            Wtem.LOGGER.error("Failed to export language file", e);
        }
    }

    class ChunkExtractor extends RegionStorageUpgrader {
        private final AbstractWHandler<CompoundTag> handler;

        protected ChunkExtractor(AbstractWHandler<CompoundTag> handler, DataFixTypes type, String folderName/*?if >=26.1 >>')'*/, int previousCopiesFileAmounts) {
            //? if >= 26.1 {
            super(WorldExtractor.this.dataFixer, type, folderName, folderName, -1, false, WorldExtractor.this.progress, previousCopiesFileAmounts, null, Int2ObjectMap.ofEntries());
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
            boolean isUpdated = false;
            ListTag list = NbtUtils.getList(compoundTag, handler.getName(), Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                isUpdated |= this.handler.handle(NbtUtils.getCompound(list, i));
            }
            if (!isUpdated) return false;
            if (this.previousWriteFuture != null) this.previousWriteFuture.join();
            this.previousWriteFuture = storage.write(chunkPos, /*?if >=26.1 >>'compoundTag'*/() -> compoundTag);
            return true;
        }
    }
}
