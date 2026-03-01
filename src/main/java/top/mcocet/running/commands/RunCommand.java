package top.mcocet.running.commands;

import xin.bbtt.mcbot.command.Command;

public class RunCommand extends Command {
    @Override
    public String getDescription() {
        return "Running plugin main command with subcommands";
    }
    
    @Override
    public String getUsage() {
        return "rung <subcommand> [arguments]";
    }

    @Override
    public String getName() {
        return "running";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"running", "rung"};
    }
}