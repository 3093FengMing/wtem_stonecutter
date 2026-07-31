package me.fengming.wtem.common.core.handler.datapack;

import java.nio.file.Path;
import java.util.function.Function;
import net.minecraft.resources.Identifier;

/**
 * @author FengMing
 */
public abstract class NonExtraResourceHandler extends ResourceHandler {
    public NonExtraResourceHandler(String path, Function<Identifier, Path> filePath) {
        super(path, filePath, null);
    }
}
