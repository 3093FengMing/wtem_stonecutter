package me.fengming.wtem.common.core.misc;

import me.fengming.wtem.common.core.TranslationContext;
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
    public void extract() {
        this.getPlayerTeams().forEach(team -> {
            String key = "team." + team.getName();
            team.setDisplayName(translate(key + ".name", team.getDisplayName()));
            team.setPlayerPrefix(translate(key + ".prefix", team.getPlayerPrefix()));
            team.setPlayerSuffix(translate(key + ".suffix", team.getPlayerSuffix()));
        });

        this.getObjectives().forEach(objective -> {
            String key = "score." + objective.getName();
            objective.setDisplayName(translate(key + ".name", objective.getDisplayName()));

            NumberFormat translatedFormat = translateNumberFormat(key + ".format", objective.numberFormat());
            if (translatedFormat != objective.numberFormat()) {
                objective.setNumberFormat(translatedFormat);
            }

            this.extractPlayerScores(objective, key);
        });
    }

    private void extractPlayerScores(Objective objective, String objectiveKey) {
        for (PlayerScoreEntry entry : this.listPlayerScores(objective)) {
            ScoreHolder owner = ScoreHolder.forNameOnly(entry.owner());
            ScoreAccess score = this.getOrCreatePlayerScore(owner, objective, false);
            String key = objectiveKey + ".player." + entry.owner();

            if (entry.display() != null) {
                score.display(translate(key + ".name", entry.display()));
            }

            NumberFormat translatedFormat =
                    translateNumberFormat(key + ".format", entry.numberFormatOverride());
            if (translatedFormat != entry.numberFormatOverride()) {
                score.numberFormatOverride(translatedFormat);
            }
        }
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
