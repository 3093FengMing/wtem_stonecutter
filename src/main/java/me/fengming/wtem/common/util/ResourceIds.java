//~ resource_key_api
package me.fengming.wtem.common.util;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * @author FengMing
 */
public final class ResourceIds {
    private ResourceIds() {}

    public static String path(String id) {
        if (id == null) return "";
        int separator = id.indexOf(':');
        return separator < 0 ? id : id.substring(separator + 1);
    }

    /**
     * Returns the path of a built-in identifier, or the unchanged id for other namespaces.
     *
     * <p>Data packs may write built-in ids with or without the {@code minecraft} namespace. Matching
     * on {@link #path(String)} alone would also match same-named ids from other namespaces, which are
     * unrelated schemas.
     */
    public static String vanillaPath(String id) {
        if (id == null) return "";
        int separator = id.indexOf(':');
        if (separator < 0) return id;
        return "minecraft".equals(id.substring(0, separator)) ? id.substring(separator + 1) : id;
    }

    public static Identifier create(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    public static String key(ResourceKey<?> key) {
        return key.identifier().toString();
    }
}
