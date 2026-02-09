package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;


public class VoxyCommands {

    public static LiteralArgumentBuilder<FabricClientCommandSource> register() {
        var imports = ClientCommandManager.literal("import")
                .then(ClientCommandManager.literal("world")
                        .then(ClientCommandManager.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importWorldSuggester)
                                .executes(VoxyCommands::importWorld)))
                .then(ClientCommandManager.literal("bobby")
                        .then(ClientCommandManager.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importBobbySuggester)
                                .executes(VoxyCommands::importBobby)))
                .then(ClientCommandManager.literal("raw")
                        .then(ClientCommandManager.argument("path", StringArgumentType.string())
                                .executes(VoxyCommands::importRaw)))
                .then(ClientCommandManager.literal("zip")
                        .then(ClientCommandManager.argument("zipPath", StringArgumentType.string())
                                .executes(VoxyCommands::importZip)
                                .then(ClientCommandManager.argument("innerPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip))))
                .then(ClientCommandManager.literal("cancel")
                        .executes(VoxyCommands::cancelImport));

        if (DHImporter.HasRequiredLibraries) {
            imports = imports
                    .then(ClientCommandManager.literal("distant_horizons")
                    .then(ClientCommandManager.argument("sqlDbPath", StringArgumentType.string())
                            .executes(VoxyCommands::importDistantHorizons)));
        }

        return ClientCommandManager.literal("voxy")//.requires((ctx)-> VoxyCommon.getInstance() != null)
                .then(ClientCommandManager.literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(ClientCommandManager.literal("info")
                        .executes(VoxyCommands::showInfo))
                .then(imports);
    }

    private static int reloadInstance(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr!=null) {
            ((IGetVoxyRenderSystem)wr).shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        var r = Minecraft.getInstance().levelRenderer;
        if (r != null) r.allChanged();
        return 0;
    }

    private static int showInfo(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        ctx.getSource().sendFeedback(Component.literal("§6[Voxy] §fGetting Voxy info..."));

        new Thread(() -> {
            try {
                StringBuilder info = new StringBuilder();
                info.append("§6═══════Voxy信息 / Voxy Info═══════§r\n\n");

                long memBufTotalSize = me.cortex.voxy.common.util.MemoryBuffer.getTotalSize();
                int memBufCount = me.cortex.voxy.common.util.MemoryBuffer.getCount();
                double memBufMB = memBufTotalSize / (1024.0 * 1024.0);

                Runtime runtime = Runtime.getRuntime();
                long maxMemory = runtime.maxMemory();
                long totalMemory = runtime.totalMemory();
                long freeMemory = runtime.freeMemory();
                long usedMemory = totalMemory - freeMemory;

                double usedMemoryMB = usedMemory / (1024.0 * 1024.0);
                double maxMemoryMB = maxMemory / (1024.0 * 1024.0);
                double voxyMemPercent = (memBufTotalSize * 100.0) / maxMemory;

                info.append(String.format("§e内存占用 / Memory Usage:§r\n"));
                info.append(String.format("Minecraft: §a%.1f MB§r / §7%.1f MB§r (§c%.1f%%§r)\n",
                        usedMemoryMB, maxMemoryMB, (usedMemory * 100.0) / maxMemory));
                info.append(String.format("Voxy: §a%.1f MB§r (§e%.2f%%§r of total)\n",
                        memBufMB, voxyMemPercent));

                info.append(String.format("\n§eMemory Buffer:§r\n"));
                info.append(String.format("缓冲区数 / Count: §a%d§r\n", memBufCount));
                info.append(String.format("总大小 / Total Size: §a%.2f MB§r\n", memBufMB));

                var cacheStats = instance.getWorldCache().getStats();
                info.append(String.format("\n§e世界缓存 / World Cache:§r\n"));
                info.append(String.format("状态 / Status: §%s%s§r\n",
                        cacheStats.currentSize > 0 ? "a" : "7",
                        cacheStats.currentSize > 0 ? "活跃 / Active":"空闲 / Idle"));
                info.append(String.format("缓存数 / Cached: §a%d§r / §7%d§r\n",
                        cacheStats.currentSize, cacheStats.maxSize));
                info.append(String.format("命中率 / Hit Rate: §a%.1f%%§r\n", cacheStats.hitRate));
                info.append(String.format("命中/未中 / Hits/Misses: §a%d§r / §c%d§r\n",
                        cacheStats.hits, cacheStats.misses));
                info.append(String.format("丢掉次数 / Drop: §e%d§r\n", cacheStats.evictions));

                info.append(String.format("\n§e任务队列 / Task Queues:§r\n"));
                info.append(String.format("摄取 / Ingest: §a%d§r\n", instance.getIngestService().getTaskCount()));
                info.append(String.format("保存 / Saving: §a%d§r\n", instance.getSavingService().getTaskCount()));

                //活跃区块数
                var activeWorlds = instance.getActiveWorlds();
                if (!activeWorlds.isEmpty()) {
                    info.append(String.format("\n§e活跃世界 / Active Worlds:§r\n"));
                    for (var world : activeWorlds) {
                        int sectionCount = world.getActiveSectionCount();
                        info.append(String.format("  区块数 / Sections: §a%d§r\n", sectionCount));
                    }
                }

                info.append("\n§6═══════════════════════════════════§r");

                Minecraft.getInstance().execute(() -> {
                    ctx.getSource().sendFeedback(Component.literal(info.toString()));
                });

            } catch (Exception e) {
                ctx.getSource().sendError(Component.literal("§c获取信息失败: " + e.getMessage()));
            }
        }, "Voxy-Info-Collector").start();

        return 0;
    }




    private static int importDistantHorizons(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
        if (!dbFile.exists()) {
            return 1;
        }
        if (dbFile.isDirectory()) {
            dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (!dbFile.exists()) {
                return 1;
            }
        }

        File dbFile_ = dbFile;
        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null)return 1;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->
                new DHImporter(dbFile_, engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter))?0:1;
    }

    private static boolean fileBasedImporter(File directory) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null) return false;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->{
            var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
    }

    private static boolean fileBasedImporter(Level level, File directory) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }

        var engine = WorldIdentifier.ofEngine(level);
        if (engine==null) return false;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->{
            var importer = new WorldImporter(engine, level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
    }

    private static int importRaw(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        return fileBasedImporter(new File(ctx.getArgument("path", String.class)))?0:1;
    }

    private static int importBobby(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(file)?0:1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
    }
    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0,str.length()-1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx+1);
            try {
                dir = dir.resolve(str.substring(0, idx));
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx+1);
        } else {
            str = "";
        }

        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (SharedSuggestionProvider.matchesSubStr(remaining, wn) || SharedSuggestionProvider.matchesSubStr(remaining, '"'+wn)) {
                    wn = str+wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException e) {}

        return sb.buildFuture();
    }

    private static int importWorld(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var name = ctx.getArgument("world_name", String.class);
        var file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase();
        if (name.endsWith("/")) {
            name = name.substring(0, name.length()-1);
        }
        if (file.resolve("level.dat").toFile().exists()) {
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                return importAllDimensionsFromServer(server, file) ? 0 : 1;
            }

            var level = Minecraft.getInstance().level;
            if (level == null) {
                return 1;
            }
            var dimFile = DimensionType.getStorageFolder(level.dimension(), file)
                    .resolve("region")
                    .toFile();
            if (!dimFile.isDirectory()) {
                return 1;
            }
            return fileBasedImporter(dimFile) ? 0 : 1;
        } else {
            if (!(name.endsWith("region"))) {
                file = file.resolve("region");
            }
            return fileBasedImporter(file.toFile())?0:1;
        }
    }

    private static boolean importAllDimensionsFromServer(MinecraftServer server, Path worldRoot) {
        boolean startedAny = false;
        boolean failedAny = false;

        for (ResourceKey<Level> levelKey : server.levelKeys()) {
            ServerLevel level = server.getLevel(levelKey);
            if (level == null) {
                continue;
            }
            var dimPath = DimensionType.getStorageFolder(levelKey, worldRoot)
                    .resolve("region")
                    .toFile();
            if (!dimPath.isDirectory()) {
                continue;
            }
            boolean started = fileBasedImporter(level, dimPath);
            startedAny |= started;
            failedAny |= !started;
        }

        return startedAny && !failedAny;
    }

    private static int importZip(CommandContext<FabricClientCommandSource> ctx) {
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception e) {}

        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        String finalInnerDir = innerDir;

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine != null) {
            return instance.getImportManager().makeAndRunIfNone(engine, () -> {
                var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
                importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
                return importer;
            }) ? 0 : 1;
        }
        return 1;
    }

    private static int cancelImport(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world != null) {
            return instance.getImportManager().cancelImport(world)?0:1;
        }
        return 1;
    }
}