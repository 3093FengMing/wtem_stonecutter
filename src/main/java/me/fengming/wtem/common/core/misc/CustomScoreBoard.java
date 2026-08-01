package me.fengming.wtem.common.core.misc;

import java.util.function.Consumer;
import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.util.ChangeTracker;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
//? if <1.21.11 {
/*import net.minecraft.util.datafix.DataFixTypes;
*///?}
//? if <1.21.5 {
/*import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
*///?} else if <1.21.11 {
/*import net.minecraft.world.level.saveddata.SavedDataType;
*///?}
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.ScoreboardSaveData;

/**
 * @author FengMing
 */
public class CustomScoreBoard extends Scoreboard {
    //? if >=1.21.11 {
    public void load(ScoreboardSaveData.Packed data) {
        data.objectives().forEach(this::loadObjective);
        data.scores().forEach(this::loadPlayerScore);
        data.displaySlots()
                .forEach(
                        (slot, name) -> {
                            Objective objective = this.getObjective(name);
                            this.setDisplayObjective(slot, objective);
                        });
        data.teams().forEach(this::loadPlayerTeam);
    }

    public ScoreboardSaveData.Packed store() {
        return new ScoreboardSaveData.Packed(
                this.packObjectives(),
                this.packPlayerScores(),
                this.packDisplaySlots(),
                this.packPlayerTeams());
    }

    //?} else if >=1.21.5 {
    /*// Describes how the scoreboard file is read into and written back out of this instance.
    // Vanilla builds the equivalent descriptor inside ServerScoreboard, which needs a running
    // server. Rebuilding it here keeps the extraction path server-free while reusing the vanilla
    // codec, so the file format stays identical.
    public SavedDataType<ScoreboardSaveData> dataType() {
        return new SavedDataType<>(
                ScoreboardSaveData.FILE_ID,
                context -> this.createData(),
                context ->
                        ScoreboardSaveData.Packed.CODEC.xmap(
                                this::createData, ScoreboardSaveData::pack),
                DataFixTypes.SAVED_DATA_SCOREBOARD);
    }

    private ScoreboardSaveData createData() {
        return new ScoreboardSaveData(this);
    }

    private ScoreboardSaveData createData(ScoreboardSaveData.Packed data) {
        ScoreboardSaveData saveData = this.createData();
        saveData.loadFrom(data);
        return saveData;
    }

    *///?} else {
    /*public SavedData.Factory<ScoreboardSaveData> dataFactory() {
        return new SavedData.Factory<>(
                this::createData, this::createData, DataFixTypes.SAVED_DATA_SCOREBOARD);
    }

    private ScoreboardSaveData createData() {
        return new ScoreboardSaveData(this);
    }

    private ScoreboardSaveData createData(CompoundTag tag, HolderLookup.Provider registries) {
        return this.createData().load(tag, registries);
    }

    *///?}
    public boolean extract() {
        ChangeTracker tracker = new ChangeTracker();
        for (var team : this.getPlayerTeams()) {
            String key = "team." + team.getName();
            try (var ignored = TranslationContext.pushSubject("team " + team.getName())) {
                store(
                        tracker,
                        team.getDisplayName(),
                        translate(key + ".name", team.getDisplayName()),
                        team::setDisplayName);
                store(
                        tracker,
                        team.getPlayerPrefix(),
                        translate(key + ".prefix", team.getPlayerPrefix()),
                        team::setPlayerPrefix);
                store(
                        tracker,
                        team.getPlayerSuffix(),
                        translate(key + ".suffix", team.getPlayerSuffix()),
                        team::setPlayerSuffix);
            }
        }

        for (Objective objective : this.getObjectives()) {
            String key = "score." + objective.getName();
            try (var ignored =
                    TranslationContext.pushSubject("objective " + objective.getName())) {
                store(
                        tracker,
                        objective.getDisplayName(),
                        translate(key + ".name", objective.getDisplayName()),
                        objective::setDisplayName);
                store(
                        tracker,
                        objective.numberFormat(),
                        translateNumberFormat(key + ".format", objective.numberFormat()),
                        objective::setNumberFormat);

                tracker.add(this.extractPlayerScores(objective, key));
            }
        }
        return tracker.isChanged();
    }

    private boolean extractPlayerScores(Objective objective, String objectiveKey) {
        ChangeTracker tracker = new ChangeTracker();
        for (PlayerScoreEntry entry : this.listPlayerScores(objective)) {
            ScoreHolder owner = ScoreHolder.forNameOnly(entry.owner());
            ScoreAccess score = this.getOrCreatePlayerScore(owner, objective, false);
            String key = objectiveKey + ".player." + entry.owner();

            try (var ignored = TranslationContext.pushSubject("player " + entry.owner())) {
                if (entry.display() != null) {
                    store(
                            tracker,
                            entry.display(),
                            translate(key + ".name", entry.display()),
                            score::display);
                }

                store(
                        tracker,
                        entry.numberFormatOverride(),
                        translateNumberFormat(key + ".format", entry.numberFormatOverride()),
                        score::numberFormatOverride);
            }
        }
        return tracker.isChanged();
    }

    /**
     * Writes {@code translated} back through {@code setter} when translation produced a new value.
     *
     * <p>Translation helpers return the original instance untouched when nothing matched, so
     * identity comparison is what distinguishes a real change from a no-op.
     */
    private static <T> void store(
            ChangeTracker tracker, T original, T translated, Consumer<T> setter) {
        if (tracker.add(translated != original)) setter.accept(translated);
    }

    private static Component translate(String key, Component component) {
        TranslationContext.setKey(key);
        return TranslationUtils.translateLiteral(component);
    }

    private static NumberFormat translateNumberFormat(String key, NumberFormat format) {
        if (!(format instanceof FixedFormat fixed)) return format;

        Component value = fixed.format(0);
        Component translated = translate(key, value);
        return translated == value ? format : new FixedFormat(translated);
    }
}
