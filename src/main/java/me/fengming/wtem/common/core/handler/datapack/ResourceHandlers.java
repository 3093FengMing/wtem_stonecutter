package me.fengming.wtem.common.core.handler.datapack;

import java.util.List;

/**
 * @author FengMing
 */
public final class ResourceHandlers {
    private static List<HandlerFactory> handlers = List.of();

    private ResourceHandlers() {}

    public static synchronized void initialize(List<HandlerFactory> defaultHandlers) {
        if (!handlers.isEmpty()) return;
        handlers = List.copyOf(defaultHandlers);
    }

    public static List<HandlerFactory> all() {
        return handlers;
    }
}
