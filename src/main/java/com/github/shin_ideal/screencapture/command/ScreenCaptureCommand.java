package com.github.shin_ideal.screencapture.command;

import com.github.shin_ideal.screencapture.client.*;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.util.List;

public class ScreenCaptureCommand extends CommandBase {

    private static final String[] SUBCOMMANDS = new String[]{"start", "stop", "clear", "show", "set", "remove",
        "status", "help"};
    private static final String[] SET_OPTIONS = new String[]{"location", "flip", "size"};

    @Override
    public String getCommandName() {
        return "screencapture";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/screencapture <start|stop|clear|show|set|remove|status|help>";
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
            case "set":
                handleSet(sender, args);
                break;
            case "remove":
                if (!CaptureWorldProjection.hasPlacement()) {
                    sender.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.YELLOW + "No world projection to remove."));
                } else {
                    CaptureWorldProjection.remove();
                    sender.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.YELLOW + "World projection removed."));
                }
                break;
            case "status":
                sender.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.AQUA + "Running: " + CaptureSession.isRunning()
                            + EnumChatFormatting.RESET
                            + " | Visible: "
                            + CaptureOverlay.isVisible()
                            + " | World: "
                            + (CaptureWorldProjection.hasPlacement()
                            ? CaptureWorldProjection.getPlacement()
                              .describe()
                            : "none")
                            + " | Frames: "
                            + CaptureSession.getFrameCount()
                            + " | URL: "
                            + CaptureSession.getCaptureUrl()));
                break;
            case "help":
            case "?":
                sendHelp(sender);
                break;
            default:
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + getCommandUsage(sender)));
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.GRAY + "Use /screencapture help for details."));
                break;
        }
    }

    private void handleSet(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + setUsage()));
            return;
        }

        String option = args[1].toLowerCase();
        try {
            switch (option) {
                case "location":
                    handleSetLocation(sender, args);
                    break;
                case "flip":
                    handleSetFlip(sender, args);
                    break;
                case "size":
                    handleSetSize(sender, args);
                    break;
                default:
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + setUsage()));
                    break;
            }
        } catch (NumberFormatException e) {
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + "Invalid number. " + setUsage()));
        } catch (Exception e) {
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + "Failed to set projection: " + e.getMessage()));
        }
    }

    private void handleSetLocation(ICommandSender sender, String[] args) {
        // /screencapture set location here
        if (args.length >= 3 && "here".equalsIgnoreCase(args[2])) {
            float width = CaptureWorldProjection.currentOrDefaultWidth();
            WorldScreenFacing facing = CaptureWorldProjection.currentFacingOrNull();
            if (!CaptureWorldProjection.setFromLook(width, facing)) {
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.RED + "Failed to place world projection."));
                return;
            }
            reportPlacement(sender, "location");
            return;
        }

        // /screencapture set location <x> <y> <z>
        if (args.length >= 5) {
            double baseX = 0.0D;
            double baseY = 0.0D;
            double baseZ = 0.0D;
            if (sender instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) sender;
                baseX = player.posX;
                baseY = player.posY;
                baseZ = player.posZ;
            }

            double x = func_110666_a(sender, baseX, args[2]);
            double y = func_110666_a(sender, baseY, args[3]);
            double z = func_110666_a(sender, baseZ, args[4]);

            WorldScreenFacing facing = CaptureWorldProjection.currentFacingOrNull();
            if (facing == null) {
                facing = defaultFacing(sender);
            }
            float width = CaptureWorldProjection.currentOrDefaultWidth();

            CaptureWorldProjection.setAt(x, y, z, facing, width);
            reportPlacement(sender, "location");
            return;
        }

        sender.addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.RED
                    + "Usage: /screencapture set location here | set location <x> <y> <z>"));
    }

    private void handleSetFlip(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + "Usage: /screencapture set flip <north|south|east|west>"));
            return;
        }

        WorldScreenFacing facing = WorldScreenFacing.fromName(args[2]);
        if (facing == null) {
            sender.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + "Unknown facing. Use north, south, east, or west."));
            return;
        }

        if (!CaptureWorldProjection.hasPlacement()) {
            // No placement yet: create at look position with this facing.
            if (!CaptureWorldProjection.setFromLook(CaptureWorldProjection.currentOrDefaultWidth(), facing)) {
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.RED + "Failed to place world projection."));
                return;
            }
        } else if (!CaptureWorldProjection.updateFacing(facing)) {
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + "Failed to update flip."));
            return;
        }

        reportPlacement(sender, "flip");
    }

    private void handleSetSize(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + "Usage: /screencapture set size <width>"));
            return;
        }

        float width = parseWidth(args[2]);

        if (!CaptureWorldProjection.hasPlacement()) {
            if (!CaptureWorldProjection.setFromLook(width, null)) {
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.RED + "Failed to place world projection."));
                return;
            }
        } else if (!CaptureWorldProjection.updateSize(width)) {
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + "Failed to update size."));
            return;
        }

        reportPlacement(sender, "size");
    }

    private void reportPlacement(ICommandSender sender, String changed) {
        WorldScreenPlacement placed = CaptureWorldProjection.getPlacement();
        sender.addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.GREEN + "Updated " + changed + ": " + placed.describe()));
        if (!CaptureSession.isRunning()) {
            sender.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.GRAY + "Capture is not running. Use /screencapture start to show video."));
        }
    }

    private static WorldScreenFacing defaultFacing(ICommandSender sender) {
        if (sender instanceof EntityPlayer) {
            return WorldScreenFacing.facingPlayer(((EntityPlayer) sender).rotationYaw);
        }
        return WorldScreenFacing.NORTH;
    }

    private static float parseWidth(String raw) {
        float width = Float.parseFloat(raw);
        if (width < 0.5F || width > 64.0F) {
            throw new NumberFormatException("width out of range");
        }
        return width;
    }

    private static String setUsage() {
        return "Usage: /screencapture set <location|flip|size> ...";
    }

    private void sendHelp(ICommandSender sender) {
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + "=== ScreenCapture help ==="));
        sender.addChatMessage(msg("start", "ローカルキャプチャサーバ起動＆ブラウザを開く"));
        sender.addChatMessage(msg("stop", "キャプチャサーバ停止"));
        sender.addChatMessage(msg("clear", "HUDオーバーレイを非表示"));
        sender.addChatMessage(msg("show", "HUDオーバーレイを再表示"));
        sender.addChatMessage(msg("remove", "空間投影を削除"));
        sender.addChatMessage(msg("status", "状態表示"));
        sender.addChatMessage(msg("help", "このヘルプ"));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "set (項目ごとに指定):"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.GRAY + "  /screencapture set location here"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.DARK_GRAY + "    視線先（その場）に配置"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.GRAY + "  /screencapture set location <x> <y> <z>"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.DARK_GRAY + "    座標指定（~ 相対可）"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.GRAY + "  /screencapture set flip <north|south|east|west>"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.DARK_GRAY + "    向きを変更"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.GRAY + "  /screencapture set size <width>"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.DARK_GRAY + "    大きさ（横幅・ブロック単位）を変更"));
        sender.addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.DARK_GRAY + "  例: set location here → set flip north → set size 3"));
    }

    private static ChatComponentText msg(String name, String desc) {
        return new ChatComponentText(
            EnumChatFormatting.YELLOW + name + EnumChatFormatting.RESET + " - " + desc);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
        }

        if (!"set".equalsIgnoreCase(args[0])) {
            return null;
        }

        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, SET_OPTIONS);
        }

        String option = args[1].toLowerCase();
        if ("location".equals(option)) {
            if (args.length == 3) {
                return getListOfStringsMatchingLastWord(args, "here", "~", "~0");
            }
            if (args.length == 4 || args.length == 5) {
                return getListOfStringsMatchingLastWord(args, "~", "~0", "~1");
            }
        } else if ("flip".equals(option) && args.length == 3) {
            return getListOfStringsMatchingLastWord(args, WorldScreenFacing.names());
        } else if ("size".equals(option) && args.length == 3) {
            return getListOfStringsMatchingLastWord(args, "1", "2", "3", "4", "8");
        }

        return null;
    }
}
