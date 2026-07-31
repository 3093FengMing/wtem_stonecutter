package me.fengming.wtem.common.core.handler.datapack;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;
import me.fengming.wtem.common.core.handler.StructureTemplateWHandler;
import me.fengming.wtem.common.util.ResourceIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * @author FengMing
 */
public class StructureHandler extends ResourceHandler {
    public static final HandlerFactory FACTORY = StructureHandler::new;

    public StructureHandler(Function<Identifier, Path> filePath, Context context) {
        super("structure", filePath, context);
    }

    @Override
    protected String fileExtension() {
        return ".nbt";
    }

    @Override
    protected boolean innerHandle(Identifier rl, IoSupplier<InputStream> supplier) {
        try (InputStream input = supplier.get()) {
            var source = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
            StructureTemplateWHandler.Result result =
                    new StructureTemplateWHandler().process(source);
            if (result.changed()) ResourceIo.writeNbt(getFilePath(rl), result.tag());
            return result.changed();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read structure " + rl, exception);
        }
    }
}
