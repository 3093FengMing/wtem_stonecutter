package me.fengming.wtem.common.core.handler.datapack;

import java.nio.file.Path;
import java.util.function.Function;
import net.minecraft.resources.Identifier;

/**
 * @author FengMing
 */
@FunctionalInterface
public interface HandlerFactory {
    ResourceHandler newHandler(Function<Identifier, Path> filePath, ResourceHandler.Context context);
}
