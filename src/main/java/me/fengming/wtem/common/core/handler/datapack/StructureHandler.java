package me.fengming.wtem.common.core.handler.datapack;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Function;
import me.fengming.wtem.common.core.handler.StructureTemplateWHandler;
import me.fengming.wtem.common.util.ResourceIo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * @author FengMing
 */
public class StructureHandler extends ResourceHandler {
    private static final FileToIdConverter STRUCTURE_CONVERTOR =
            new FileToIdConverter("structure", ".nbt");

    public static final HandlerFactory FACTORY = StructureHandler::new;

    public StructureHandler(Function<Identifier, Path> filePath, Context context) {
        super("structure", filePath, context);
    }

    @Override
    protected void innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
        StructureTemplateManager m = this.context.structureManager();
        if (m == null) return;
        StructureTemplate structure = m.get(STRUCTURE_CONVERTOR.fileToId(rl)).orElse(null);
        CompoundTag modified = new StructureTemplateWHandler().handle(structure);
        ResourceIo.writeNbt(getFilePath(rl), modified);
    }
}
