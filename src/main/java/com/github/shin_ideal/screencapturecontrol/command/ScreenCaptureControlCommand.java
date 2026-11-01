package com.github.shin_ideal.screencapturecontrol.command;

import com.github.shin_ideal.screencapturecontrol.client.*;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.util.Collections;
import java.util.List;

public class ScreenCaptureControlCommand extends CommandBase {

    private static final String[] SUBCOMMANDS = new String[]{
        "start", "stop", "window", "interact", "scale", "cursor", "clear", "show", "set", "remove", "status", "help"
    };
    private static final String[] SET_OPTIONS = new String[]{"location", "flip", "size"};

    @Override
    public String getCommandName() {
        return "screencapturecontrol";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/screencapturecontrol <start|stop|window|interact|scale|cursor|clear|show|set|remove|status|help>";
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
                CaptureOverlay.setVisible(true);
                try {
                    CaptureSession.start();
                    sender.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.GREEN + "C++ JNI ネイティブキャプチャ (ScreenCaptureNative.dll) を開始しました！"));
                    sender.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.GRAY + "操作モード開始: [O] キー (終了: [Shift+ESC])"));
                    CaptureSession.requestWindowList();
                } catch (Exception e) {
                    sender.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.RED + "ネイティブキャプチャの初期化に失敗しました: " + e.getMessage()));
                }
                break;

            case "window":
                if (!CaptureSession.isRunning() || !CaptureSession.isConnected()) {
                    try {
                        CaptureOverlay.setVisible(true);
                        CaptureSession.start();
                    } catch (Exception e) {
                        sender.addChatMessage(
                            new ChatComponentText(
                                EnumChatFormatting.RED + "キャプチャの開始に失敗しました: " + e.getMessage()));
                        break;
                    }
                }
                if (args.length == 1) {
                    CaptureSession.requestWindowList();
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < args.length; i++) {
                        if (i > 1) sb.append(" ");
                        sb.append(args[i]);
                    }
                    String query = sb.toString().trim();

                    if ("test".equalsIgnoreCase(query)) {
                        CaptureSession.selectTestPattern();
                        sender.addChatMessage(
                            new ChatComponentText(EnumChatFormatting.YELLOW + "テストパターン (60fps カラーバー) に切り替えました。"));
                    } else if ("desktop".equalsIgnoreCase(query) || "screen".equalsIgnoreCase(query)) {
                        if (CaptureSession.selectDesktop()) {
                            sender.addChatMessage(
                                new ChatComponentText(EnumChatFormatting.GREEN + "投影対象を [デスクトップ全体 (全画面)] に切り替えました。"));
                        } else {
                            sender.addChatMessage(
                                new ChatComponentText(EnumChatFormatting.RED + "デスクトップ全体のキャプチャ開始に失敗しました。"));
                        }
                    } else {
                        boolean isNumeric = false;
                        long num = -1;
                        try {
                            num = Long.parseLong(query);
                            isNumeric = true;
                        } catch (NumberFormatException ignored) {
                        }

                        if (isNumeric) {
                            if (num == 0) {
                                if (CaptureSession.selectDesktop()) {
                                    sender.addChatMessage(
                                        new ChatComponentText(EnumChatFormatting.GREEN + "投影対象を [デスクトップ全体 (全画面)] に切り替えました。"));
                                } else {
                                    sender.addChatMessage(
                                        new ChatComponentText(EnumChatFormatting.RED + "デスクトップ全体のキャプチャ開始に失敗しました。"));
                                }
                            } else {
                                CaptureSession.WindowEntry entry = CaptureSession.getCachedWindowByIndex((int) num);
                                if (entry != null) {
                                    if (entry.hwnd == 0) {
                                        CaptureSession.selectDesktop();
                                        sender.addChatMessage(
                                            new ChatComponentText(EnumChatFormatting.GREEN + "投影対象を [デスクトップ全体 (全画面)] に切り替えました。"));
                                    } else if (CaptureSession.selectWindow(entry.hwnd)) {
                                        sender.addChatMessage(
                                            new ChatComponentText(
                                                EnumChatFormatting.AQUA + "投影対象を [" + num + "] " + entry.title + " (" + entry.process + ") に切り替えました！"));
                                    } else {
                                        sender.addChatMessage(
                                            new ChatComponentText(
                                                EnumChatFormatting.RED + "ウィンドウ [" + entry.title + "] のキャプチャ開始に失敗しました（最小化されていないか確認してください）。"));
                                    }
                                } else {
                                    CaptureSession.WindowEntry matched = CaptureSession.selectWindowByFilter(query);
                                    if (matched != null) {
                                        sender.addChatMessage(
                                            new ChatComponentText(
                                                EnumChatFormatting.AQUA + "投影対象を [" + matched.title + "] (" + matched.process + ") に切り替えました！"));
                                    } else {
                                        sender.addChatMessage(
                                            new ChatComponentText(
                                                EnumChatFormatting.RED + "番号または条件に一致するウィンドウが見つかりません: " + query));
                                    }
                                }
                            }
                        } else {
                            CaptureSession.WindowEntry matched = CaptureSession.selectWindowByFilter(query);
                            if (matched != null) {
                                sender.addChatMessage(
                                    new ChatComponentText(
                                        EnumChatFormatting.AQUA + "投影対象を [" + matched.title + "] (" + matched.process + ") に切り替えました！"));
                            } else {
                                sender.addChatMessage(
                                    new ChatComponentText(
                                        EnumChatFormatting.RED + "条件に一致するウィンドウが見つかりません: " + query));
                            }
                        }
                    }
                }
                break;

            case "stop":
                CaptureSession.stop();
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.YELLOW + "ネイティブ画面キャプチャを停止しました。"));
                break;

            case "interact":
                if (!CaptureSession.isRunning() || !CaptureSession.isConnected()) {
                    sender.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.RED + "キャプチャが開始されていません。先に /screencapturecontrol start を実行してください。"));
                    break;
                }
                // Schedule opening GUI on next render tick so GuiChat's displayGuiScreen(null) doesn't instantly close it
                CaptureKeyHandler.requestOpenInteractGui();
                break;

            case "scale":
                if (args.length == 1) {
                    sender.addChatMessage(
                        new ChatComponentText(EnumChatFormatting.GRAY + "Usage: /screencapturecontrol scale <half|full>"));
                    sender.addChatMessage(
                        new ChatComponentText(EnumChatFormatting.DARK_GRAY + "  full: 1:1 等倍解像度 (デフォルト・高精細)"));
                    sender.addChatMessage(
                        new ChatComponentText(EnumChatFormatting.DARK_GRAY + "  half: 1/2 解像度 (省帯域)"));
                } else {
                    String mode = args[1].toLowerCase();
                    CaptureSession.setScaleMode(mode);
                    sender.addChatMessage(
                        new ChatComponentText(EnumChatFormatting.GREEN + "解像度スケールを [" + mode + "] に設定しました。"));
                }
                break;

            case "cursor":
                if (args.length == 1) {
                    sender.addChatMessage(
                        new ChatComponentText(EnumChatFormatting.GRAY + "Usage: /screencapturecontrol cursor <on|off>"));
                } else {
                    String opt = args[1].toLowerCase();
                    boolean enable = "on".equals(opt) || "true".equals(opt) || "1".equals(opt);
                    CaptureSession.setCursorCapture(enable);
                    sender.addChatMessage(
                        new ChatComponentText(EnumChatFormatting.GREEN + "OSカーソル焼き込み表示を [" + (enable ? "ON" : "OFF") + "] に設定しました。"));
                }
                break;

            case "clear":
                CaptureOverlay.setVisible(false);
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.YELLOW + "HUD オーバーレイを非表示にしました。"));
                break;

            case "show":
                CaptureOverlay.setVisible(true);
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.GREEN + "HUD オーバーレイを表示しました。"));
                break;

            case "set":
                handleSet(sender, args);
                break;

            case "remove":
                if (!CaptureWorldProjection.hasPlacement()) {
                    sender.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.YELLOW + "削除するワールド空間スクリーンがありません。"));
                } else {
                    CaptureWorldProjection.remove();
                    sender.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.YELLOW + "ワールド空間スクリーンを削除しました。"));
                }
                break;

            case "status":
                sender.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.AQUA + "Running: " + CaptureSession.isRunning()
                            + EnumChatFormatting.RESET
                            + " | CaptureFPS: " + String.format("%.1f", NativeCaptureBridge.getCaptureFps())
                            + " | Visible: " + CaptureOverlay.isVisible()
                            + " | World: " + (CaptureWorldProjection.hasPlacement()
                                ? CaptureWorldProjection.getPlacement().describe()
                                : "none")
                            + " | Frames: " + CaptureSession.getFrameCount()
                            + " | Target: " + NativeCaptureBridge.getTargetSummary()));
                break;

            case "help":
            case "?":
                sendHelp(sender);
                break;

            default:
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + getCommandUsage(sender)));
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.GRAY + "Use /screencapturecontrol help for details."));
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
                    + "Usage: /screencapturecontrol set location here | set location <x> <y> <z>"));
    }

    private void handleSetFlip(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + "Usage: /screencapturecontrol set flip <north|south|east|west>"));
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
            if (!CaptureWorldProjection.setFromLook(CaptureWorldProjection.currentOrDefaultWidth(), facing)) {
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.RED + "Failed to place world projection."));
                return;
            }
        } else if (!CaptureWorldProjection.updateFacing(facing)) {
            sender.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + "Failed to update flip."));
            return;
        }

        reportPlacement(sender, "flip");
    }

    private void handleSetSize(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + "Usage: /screencapturecontrol set size <width>"));
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
                new ChatComponentText(
                    EnumChatFormatting.RED + "Failed to update size."));
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
                    EnumChatFormatting.GRAY + "Capture is not running. Use /screencapturecontrol start to show video."));
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
        return "Usage: /screencapturecontrol set <location|flip|size> ...";
    }

    private void sendHelp(ICommandSender sender) {
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + "=== ScreenCaptureControlMod (C++ JNI) Help ==="));
        sender.addChatMessage(msg("start", "C++ JNI ネイティブ画面キャプチャを開始"));
        sender.addChatMessage(msg("window", "キャプチャ対象一覧表示・選択 (/screencapturecontrol window <番号|名前|desktop>)"));
        sender.addChatMessage(msg("interact", "操作モードを開始 ([O]キーでも可 / [Shift+ESC]で終了)"));
        sender.addChatMessage(msg("scale", "解像度スケール変更 (/screencapturecontrol scale <full|half>)"));
        sender.addChatMessage(msg("cursor", "OSカーソル焼き込み変更 (/screencapturecontrol cursor <on|off>)"));
        sender.addChatMessage(msg("stop", "キャプチャを停止"));
        sender.addChatMessage(msg("clear", "HUDオーバーレイを非表示"));
        sender.addChatMessage(msg("show", "HUDオーバーレイを再表示"));
        sender.addChatMessage(msg("remove", "空間投影を削除"));
        sender.addChatMessage(msg("status", "状態・FPS・ターゲット表示"));
        sender.addChatMessage(msg("help", "このヘルプ"));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "set (項目ごとに指定):"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.GRAY + "  /screencapturecontrol set location here"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.DARK_GRAY + "    視線先（その場）に配置"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.GRAY + "  /screencapturecontrol set location <x> <y> <z>"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.DARK_GRAY + "    座標指定（~ 相対可）"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.GRAY + "  /screencapturecontrol set flip <north|south|east|west>"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.DARK_GRAY + "    向きを変更"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.GRAY + "  /screencapturecontrol set size <width>"));
        sender.addChatMessage(
            new ChatComponentText(EnumChatFormatting.DARK_GRAY + "    大きさ（横幅・ブロック単位）を変更"));
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

        if ("window".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                List<String> options = new java.util.ArrayList<>();
                options.add("desktop");
                options.add("screen");
                options.add("test");
                for (CaptureSession.WindowEntry w : CaptureSession.getCachedWindows()) {
                    options.add(String.valueOf(w.index));
                    if (w.process != null && !w.process.isEmpty() && !"Desktop".equalsIgnoreCase(w.process)) {
                        options.add(w.process);
                    }
                }
                return getListOfStringsMatchingLastWord(args, options.toArray(new String[0]));
            }
            return null;
        }

        if ("scale".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                return getListOfStringsMatchingLastWord(args, "full", "half");
            }
            return null;
        }

        if ("cursor".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                return getListOfStringsMatchingLastWord(args, "on", "off");
            }
            return null;
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
