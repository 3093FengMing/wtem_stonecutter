package me.fengming.wtem.common.core.misc;

import me.fengming.wtem.common.core.extraction.TranslationContext;
import me.fengming.wtem.common.util.TranslationUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
//? if <1.21.11 {
/*import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
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

    //?} else {
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
        boolean changed = false;
        for (var team : this.getPlayerTeams()) {
            String key = "team." + team.getName();
            Component displayName = translate(key + ".name", team.getDisplayName());
            Component prefix = translate(key + ".prefix", team.getPlayerPrefix());
            Component suffix = translate(key + ".suffix", team.getPlayerSuffix());
            if (displayName != team.getDisplayName()) {
                team.setDisplayName(displayName);
                changed = true;
            }
            if (prefix != team.getPlayerPrefix()) {
                team.setPlayerPrefix(prefix);
                changed = true;
            }
            if (suffix != team.getPlayerSuffix()) {
                team.setPlayerSuffix(suffix);
                changed = true;
            }
        }

        for (Objective objective : this.getObjectives()) {
            String key = "score." + objective.getName();
            Component displayName = translate(key + ".name", objective.getDisplayName());
            if (displayName != objective.getDisplayName()) {
                objective.setDisplayName(displayName);
                changed = true;
            }

            NumberFormat translatedFormat = translateNumberFormat(key + ".format", objective.numberFormat());
            if (translatedFormat != objective.numberFormat()) {
                objective.setNumberFormat(translatedFormat);
                changed = true;
            }

            changed |= this.extractPlayerScores(objective, key);
        }
        return changed;
    }

    private boolean extractPlayerScores(Objective objective, String objectiveKey) {
        boolean changed = false;
        for (PlayerScoreEntry entry : this.listPlayerScores(objective)) {
            ScoreHolder owner = ScoreHolder.forNameOnly(entry.owner());
            ScoreAccess score = this.getOrCreatePlayerScore(owner, objective, false);
            String key = objectiveKey + ".player." + entry.owner();

            if (entry.display() != null) {
                Component display = translate(key + ".name", entry.display());
                if (display != entry.display()) {
                    score.display(display);
                    changed = true;
                }
            }

            NumberFormat translatedFormat =
                    translateNumberFormat(key + ".format", entry.numberFormatOverride());
            if (translatedFormat != entry.numberFormatOverride()) {
                score.numberFormatOverride(translatedFormat);
                changed = true;
            }
        }
        return changed;
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
