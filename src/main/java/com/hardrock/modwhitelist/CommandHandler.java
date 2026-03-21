package com.hardrock.modwhitelist;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

public final class CommandHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("modwhitelist")
                        .requires(CommandHandler::isAdmin)

                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    Modwhitelist.reloadConfig();
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[Modwhitelist] Config reloaded."),
                                            true
                                    );
                                    return 1;
                                })
                        )

                        .then(Commands.literal("generate")
                                .executes(ctx -> {
                                    try {
                                        Modwhitelist.generateAndWriteHardcoreConfig();

                                        // reload to reflect the saved file (optional, but clean)
                                        Modwhitelist.reloadConfig();

                                        Modwhitelist.Config c = getCurrentConfigForInfo();
                                        int allowed = (c == null || c.allowed == null) ? 0 : c.allowed.size();
                                        int files = (c == null || c.allowedFiles == null) ? 0 : c.allowedFiles.size();

                                        ctx.getSource().sendSuccess(() ->
                                                Component.literal(
                                                        "[Modwhitelist] Generated and wrote modwhitelist.json. allowed=" + allowed + ", allowedFiles=" + files
                                                ),
                                                true
                                        );
                                    } catch (Exception ex) {
                                        ctx.getSource().sendFailure(
                                                Component.literal("[Modwhitelist] Generate failed: " + ex.getMessage())
                                        );
                                    }
                                    return 1;
                                })
                        )



                        .then(Commands.literal("collectclientonly")
                                .then(Commands.literal("on").executes(ctx -> {
                                    Modwhitelist.setCollectClientOnly(true);
                                    Modwhitelist.setStrict(false);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[Modwhitelist] collectClientOnly enabled."), true);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    Modwhitelist.setCollectClientOnly(false);
                                    Modwhitelist.setStrict(true);

                                    ctx.getSource().sendSuccess(() -> Component.literal("[Modwhitelist] collectClientOnly disabled."), true);
                                    return 1;
                                }))
                        )

                        .then(Commands.literal("clear")
                                .executes(ctx -> {
                                    Modwhitelist.clearClientOnlyFiles();
                                    ctx.getSource().sendSuccess(() -> Component.literal("[Modwhitelist] Cleared clientOnlyFiles."), true);
                                    return 1;
                                })
                        )
        );
    }

    private static boolean isAdmin(CommandSourceStack src) {
        return src.hasPermission(3);
    }

    private static Modwhitelist.Config getCurrentConfigForInfo() {
        try {
            // Not elegant, but avoids exposing internal fields: just reload and rely on Modwhitelist internals
            // If you want it cleaner: add a public getter in Modwhitelist.
            java.lang.reflect.Field f = Modwhitelist.class.getDeclaredField("config");
            f.setAccessible(true);
            return (Modwhitelist.Config) f.get(null);
        } catch (Exception ignored) {
            return null;
        }
    }

}
