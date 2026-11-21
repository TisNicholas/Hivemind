package com.hivemind.mobs;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

public class CommandHiveMind extends CommandBase {

    @Override
    public String getName() {
        return "hivemind";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/hivemind <strategy|mobs [name]|player [name]|live|stats|save|reset>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        String sub = args.length > 0 ? args[0].toLowerCase() : "stats";
        switch (sub) {
            case "strategy":
                for (String line : HiveManager.INSTANCE.brain.strategyReport()) {
                    sender.sendMessage(new TextComponentString(line));
                }
                break;
            case "mobs":
                for (String line : HiveManager.INSTANCE.brain.mobReport(args.length > 1 ? args[1] : null)) {
                    sender.sendMessage(new TextComponentString(line));
                }
                break;
            case "player":
                for (String line : HiveManager.INSTANCE.playerReport(args.length > 1 ? args[1] : null)) {
                    sender.sendMessage(new TextComponentString(line));
                }
                break;
            case "live":
                for (String line : HiveManager.INSTANCE.liveReport(getCommandSenderAsPlayer(sender))) {
                    sender.sendMessage(new TextComponentString(line));
                }
                break;
            case "stats":
                for (String line : HiveManager.INSTANCE.brain.describe()) {
                    sender.sendMessage(new TextComponentString(line));
                }
                break;
            case "save":
                HiveManager.INSTANCE.saveBrain();
                sender.sendMessage(new TextComponentString("HiveMind brain saved."));
                break;
            case "reset":
                HiveManager.INSTANCE.resetBrain();
                sender.sendMessage(new TextComponentString("HiveMind brain wiped. It will start learning from scratch."));
                break;
            default:
                sender.sendMessage(new TextComponentString(getUsage(sender)));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos targetPos) {
        return getListOfStringsMatchingLastWord(args, "strategy", "mobs", "player", "live", "stats", "save", "reset");
    }
}
