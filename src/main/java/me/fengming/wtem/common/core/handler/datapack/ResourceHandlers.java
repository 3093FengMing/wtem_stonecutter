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

    /**
     * Names the registry directories the registered handlers read, in processing order.
     *
     * <p>The configuration file lists these so a user can discover which resource kinds may be
     * switched off. A handler carries its directory name on the instance rather than on its factory, so
     * the names are read off throwaway handlers built with an empty context: nothing but the name is
     * asked of them.
     */
    public static List<String> directories() {
        ResourceHandler.Context context = ResourceHandler.Context.of(null, null, null, null);
        return handlers.stream()
                .map(factory -> factory.newHandler(rl -> null, context).getPath())
                .distinct()
                .toList();
    }
}
