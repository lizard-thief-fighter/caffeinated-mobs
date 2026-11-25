package caffeinated_mobs.extinction;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;

final class ExtinctionCommands {
    private static final DynamicCommandExceptionType UNKNOWN_POOL =
            new DynamicCommandExceptionType(pool -> Component.literal("Unknown extinction pool " + pool));

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("caffeinated_mobs").requires(s -> s.hasPermission(2))
                .then(Commands.literal("extinction")
                        .then(Commands.literal("query").executes(ExtinctionCommands::query))
                        .then(Commands.literal("set").then(pool().executes(c -> edit(c, pool(c), true, 0))
                                .then(radius().executes(c -> edit(c, pool(c), true, radius(c))))))
                        .then(Commands.literal("clear").then(pool().executes(c -> edit(c, pool(c), false, 0))
                                .then(radius().executes(c -> edit(c, pool(c), false, radius(c))))))
                        .then(Commands.literal("clear_all").executes(c -> edit(c, null, false, 0))
                                .then(radius().executes(c -> edit(c, null, false, radius(c)))))
                        .then(Commands.literal("end_period").executes(ExtinctionCommands::endPeriod))
                        .then(Commands.literal("reload").executes(ExtinctionCommands::reload))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> pool() {
        return Commands.argument("pool", ResourceLocationArgument.id())
                .suggests((c, b) -> SharedSuggestionProvider.suggestResource(Pools.all(), b));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> radius() {
        return Commands.argument("radius", IntegerArgumentType.integer(0, 16));
    }

    private static ResourceLocation pool(CommandContext<CommandSourceStack> c) {
        return ResourceLocationArgument.getId(c, "pool");
    }

    private static int radius(CommandContext<CommandSourceStack> c) {
        return IntegerArgumentType.getInteger(c, "radius");
    }

    private static int query(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPos.containing(c.getSource().getPosition());
        int rx = pos.getX() >> 7, rz = pos.getZ() >> 7;
        long key = ExtinctionState.key(rx, rz);
        ExtinctionData data = Extinction.data(level);
        RegionState region = data.state.regions().get(key);
        Object2IntMap<ResourceLocation> pending = data.pendingKills(key);
        Set<ResourceLocation> pools = new TreeSet<>(pending.keySet());
        if (region != null) {
            pools.addAll(region.pressure.keySet());
            pools.addAll(region.peak.keySet());
            pools.addAll(region.banked.keySet());
            pools.addAll(region.permanent);
        }
        StringBuilder out = new StringBuilder("Region " + rx + ", " + rz + ":");
        if (pools.isEmpty()) out.append(" no pressure");
        for (ResourceLocation pool : pools) {
            out.append("\n  ").append(pool)
                    .append(": level ").append(region == null ? 0 : region.levels.getByte(pool))
                    .append(" (set from ").append(region == null ? 0 : region.peak.getInt(pool)).append(")")
                    .append(", pressure ").append(region == null ? 0 : region.pressure.getInt(pool))
                    .append(" (+").append(pending.getInt(pool)).append(" today")
                    .append(region == null || region.banked.getInt(pool) == 0 ? "" : ", +" + region.banked.getInt(pool) + " banked")
                    .append(")");
        }
        if (region != null && region.capFactor != null) {
            for (MobCategory category : MobCategory.values()) {
                float f = region.capFactor[category.ordinal()];
                if (f < 1) out.append("\n  ").append(category.getName()).append(" cap ×").append(String.format("%.2f", f));
            }
        }
        c.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
        return pools.size();
    }

    private static int edit(CommandContext<CommandSourceStack> c, @Nullable ResourceLocation pool, boolean permanent, int radius)
            throws CommandSyntaxException {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPos.containing(c.getSource().getPosition());
        if (pool == null) {
            Settings settings = Settings.current();
            var keys = Extinction.regionsAround(pos, radius);
            Extinction.data(level).update(s -> s.edit(keys, null, null, false, settings));
        } else {
            if (Pools.category(pool) == null) throw UNKNOWN_POOL.create(pool);
            Extinction.setPermanent(level, pos, radius, pool, permanent);
        }
        String what = pool == null ? "all pools" : pool.toString();
        String verb = permanent ? "Set level 3 for " : "Cleared ";
        c.getSource().sendSuccess(() -> Component.literal(verb + what + " within " + radius + " region(s)"), true);
        return 1;
    }

    private static int endPeriod(CommandContext<CommandSourceStack> c) {
        ExtinctionData data = Extinction.data(c.getSource().getLevel());
        data.endDay(data.day(), data.day(), true);
        c.getSource().sendSuccess(() -> Component.literal("Ended every pool's period now: kills added, levels set, one period's decay taken off"), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        if (!Extinction.reload(c.getSource().getServer())) {
            c.getSource().sendFailure(Component.literal("Couldn't load config/" + Settings.FILE + "; see the log. Kept the old settings."));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Reloaded config/" + Settings.FILE), true);
        return 1;
    }

    private ExtinctionCommands() {}
}
