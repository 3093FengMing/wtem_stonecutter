package me.fengming.wtem.common.core.handler.datapack;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * @author FengMing
 */
public class ResourceHandlers {
    public static final List<HandlerFactory> HANDLERS = new ArrayList<>();

    public static void addHandler(HandlerFactory factory) {
        HANDLERS.add(factory);
    }

    public static Stream<HandlerFactory> getStream() {
        return HANDLERS.stream();
    }
}
