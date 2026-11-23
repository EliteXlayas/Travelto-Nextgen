package dev.travelto.mixin

import dev.travelto.LiveStatsScreen
import it.unimi.dsi.fastutil.objects.Object2IntMap
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.achievement.StatsScreen
import net.minecraft.stats.Stat
import net.minecraft.stats.StatsCounter
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

@Mixin(StatsCounter::class)
interface StatsCounterAccess {
    @Accessor("stats")
    fun traveltoStats(): Object2IntMap<Stat<*>>
}

@Mixin(PauseScreen::class)
abstract class LiveStatsMixin {
    @Redirect(
        method = ["lambda\$createPauseMenu\$2"],
        at = [At(value = "NEW", target = "(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/stats/StatsCounter;)Lnet/minecraft/client/gui/screens/achievement/StatsScreen;")]
    )
    private fun traveltoLiveStats(parent: Screen, stats: StatsCounter): StatsScreen = LiveStatsScreen(parent, stats)
}
