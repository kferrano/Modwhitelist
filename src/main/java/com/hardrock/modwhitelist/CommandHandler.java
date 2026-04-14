package com.hardrock.modwhitelist;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

public final class CommandHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private CommandHandler() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("modwhitelist")
                        .requires(CommandHandler::isAdmin)

                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    Modwhitelist.reloadConfig();
                                    ctx.getSource().sendSuccess(() -> Component.literal("[Modwhitelist] Configs reloaded."), true);
                                    return 1;
                                })
                        )

                        .then(Commands.literal("init")
                                .executes(ctx -> {
                                    try {
                                        Modwhitelist.initializeEmptyConfigs();
                                        ctx.getSource().sendSuccess(() -> Component.literal("[Modwhitelist] Initialized multi-file configs."), true);
                                        return 1;
                                    } catch (Exception e) {
                                        LOGGER.error("[Modwhitelist] Failed to initialize configs", e);
                                        ctx.getSource().sendFailure(Component.literal("[Modwhitelist] Failed to initialize configs. Check server log."));
                                        return 0;
                                    }
                                })
                        )

                        .then(Commands.literal("collect")
                                .then(Commands.literal("on")
                                        .executes(ctx -> {
                                            Modwhitelist.setCollectMode(true);
                                            ctx.getSource().sendSuccess(() -> Component.literal("[Modwhitelist] collectMode = true (strict=false)"), true);
                                            return 1;
                                        })
                                )
                                .then(Commands.literal("off")
                                        .executes(ctx -> {
                                            Modwhitelist.setCollectMode(false);
                                            Modwhitelist.setStrict(true);
                                            ctx.getSource().sendSuccess(() -> Component.literal("[Modwhitelist] collectMode = false"), true);
                                            return 1;
                                        })
                                )
                                .then(Commands.literal("clear")
                                        .executes(ctx -> {
                                            Modwhitelist.clearAutoCollectedManifests();
                                            ctx.getSource().sendSuccess(() -> Component.literal("[Modwhitelist] Cleared both_side_required, client_optional and server_only."), true);
                                            return 1;
                                        })
                                )
                        )
        );
    }

    private static boolean isAdmin(CommandSourceStack src) {
        return src.hasPermission(3);
    }
}