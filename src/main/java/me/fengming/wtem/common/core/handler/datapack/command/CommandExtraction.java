package me.fengming.wtem.common.core.handler.datapack.command;

/** Result of processing one logical function command.
 *
 * @author FengMing
 */
record CommandExtraction(String value, boolean changed) {
    static CommandExtraction unchanged(String value) {
        return new CommandExtraction(value, false);
    }

    static CommandExtraction changed(String value) {
        return new CommandExtraction(value, true);
    }
}
