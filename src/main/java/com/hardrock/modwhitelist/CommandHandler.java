package com.hardrock.modwhitelist;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

public final class CommandHandler extends CommandBase {

    @Override
    public String getCommandName() {
        return "modwhitelist";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/modwhitelist <reload|init|collect on|off|clear>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 3;
    }

    @Override
    public void processCommand(
            ICommandSender sender,
            String[] args
    ) {
        if (args.length == 0) {
            sender.addChatMessage(
                    new ChatComponentText(
                            getCommandUsage(sender)
                    )
            );
            return;
        }

        if ("reload".equalsIgnoreCase(args[0])) {
            Modwhitelist.reloadConfig();

            sender.addChatMessage(
                    new ChatComponentText(
                            "[ModWhitelist] Configs reloaded."
                    )
            );
            return;
        }

        if ("init".equalsIgnoreCase(args[0])) {
            try {
                Modwhitelist.initializeEmptyConfigs();

                sender.addChatMessage(
                        new ChatComponentText(
                                "[ModWhitelist] Configs initialized."
                        )
                );
            } catch (Exception e) {
                sender.addChatMessage(
                        new ChatComponentText(
                                "[ModWhitelist] Failed to initialize configs. Check server log."
                        )
                );
            }

            return;
        }

        if ("collect".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                sender.addChatMessage(
                        new ChatComponentText(
                                "/modwhitelist collect <on|off|clear>"
                        )
                );
                return;
            }

            if ("on".equalsIgnoreCase(args[1])) {
                Modwhitelist.setCollectMode(true);

                sender.addChatMessage(
                        new ChatComponentText(
                                "[ModWhitelist] collectMode = true"
                        )
                );
                return;
            }

            if ("off".equalsIgnoreCase(args[1])) {
                Modwhitelist.setCollectMode(false);
                Modwhitelist.setStrict(true);

                sender.addChatMessage(
                        new ChatComponentText(
                                "[ModWhitelist] collectMode = false, strict = true"
                        )
                );
                return;
            }

            if ("clear".equalsIgnoreCase(args[1])) {
                Modwhitelist.clearAutoCollectedManifests();

                sender.addChatMessage(
                        new ChatComponentText(
                                "[ModWhitelist] Auto-collected manifests cleared."
                        )
                );
                return;
            }

            sender.addChatMessage(
                    new ChatComponentText(
                            "/modwhitelist collect <on|off|clear>"
                    )
            );
            return;
        }

        sender.addChatMessage(
                new ChatComponentText(
                        getCommandUsage(sender)
                )
        );
    }
}