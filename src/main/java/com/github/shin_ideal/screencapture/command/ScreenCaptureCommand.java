package com.github.shin_ideal.screencapture.command;

import com.github.shin_ideal.screencapture.client.CaptureOverlay;
import com.github.shin_ideal.screencapture.client.CaptureSession;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

public class ScreenCaptureCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "screencapture";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/screencapture [start|stop|clear|show|status]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String action = args.length == 0 ? "start" : args[0].toLowerCase();

        switch (action) {
            case "start":
                try {
                    CaptureSession.start();
                    CaptureOverlay.setVisible(true);
                    sender.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.GREEN + "Screen capture started. Browser opened at "
                                + CaptureSession.getCaptureUrl()));
                    sender.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.GRAY
                                + "Click \"Share screen\" in the browser and pick a window/screen."));
                } catch (Exception e) {
                    sender.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.RED + "Failed to start capture: " + e.getMessage()));
                }
                break;
            case "stop":
                CaptureSession.stop();
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.YELLOW + "Screen capture stopped."));
                break;
            case "clear":
                CaptureOverlay.setVisible(false);
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.YELLOW + "Capture overlay hidden."));
                break;
            case "show":
                CaptureOverlay.setVisible(true);
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.GREEN + "Capture overlay shown."));
                break;
            case "status":
                sender.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.AQUA + "Running: " + CaptureSession.isRunning()
                            + EnumChatFormatting.RESET
                            + " | Visible: "
                            + CaptureOverlay.isVisible()
                            + " | Frames: "
                            + CaptureSession.getFrameCount()
                            + " | URL: "
                            + CaptureSession.getCaptureUrl()));
                break;
            default:
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + getCommandUsage(sender)));
                break;
        }
    }
}
