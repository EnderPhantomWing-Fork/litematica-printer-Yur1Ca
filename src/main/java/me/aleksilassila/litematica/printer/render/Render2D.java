package me.aleksilassila.litematica.printer.render;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.WorkingModeType;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.handler.GuiBlockInfo;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.render.Render2DUtils;
import net.minecraft.client.Minecraft;

import java.awt.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一的 2D 渲染管理器，负责所有调试信息和 HUD 的绘制。
 * 由 MixinGui 在每帧调用 render() 方法触发。
 */
public class Render2D {
    public static final Render2D INSTANCE = new Render2D();

    private static final int DEBUG_PADDING = 4;
    private static final int DEBUG_LINE_HEIGHT = 12;
    private static final int MIN_COLUMN_WIDTH = 120;
    private static final int SIDE_MARGIN = 10;
    private static final int COLUMN_SPACING = DEBUG_PADDING * 3;
    private static final int COMMON_INFO_OFFSET_Y = 10;
    private static final int HUD_PADDING = 6;
    private static final int HUD_LINE_HEIGHT = 12;

    private Render2D() {
    }

    /**
     * 主渲染入口，由 Mixin 每帧调用。
     * 注意：调用前必须已通过 Render2DUtils.initGuiGraphics 或 initMatrix 设置好渲染上下文。
     */
    public void render(float scaledWidth, float scaledHeight) {
        // 确保底层渲染工具已初始化
        Render2DUtils.ensureInitialized();

//        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
//        sword.setDamageValue(100);
//        sword.setCount(64);

//        int y = 50;
//        // 绘制物品图标 + 装饰
//        Render2DUtils.drawItemWithDecorations(sword, 100, y);
//        y += 24;
//        // 如果你只想绘制物品图标本身（不显示数量、耐久条）
//        Render2DUtils.drawItem(sword, 100, y);
//        y += 24;
//        // 绘制方块图标本身
//        Render2DUtils.drawBlock(Blocks.DIAMOND_BLOCK, 100, y);
//        y += 24;
//        // 绘制方块图标，并自动显示数量、耐久条等装饰
//        Render2DUtils.drawBlockWithDecorations(Blocks.CHEST, 100, y);
//        y += 24;
//        // 组合方法
//        Render2DUtils.drawItemWithLabel(sword, 100, y, sword.getItemName().getString(), Color.WHITE, true);

        if (Configs.Core.DEBUG_OUTPUT.getBooleanValue()) {
            drawDebugInfo(scaledWidth, scaledHeight);
        }

        if (Configs.Core.RENDER_HUD.getBooleanValue()) {
            drawHudInfo(scaledWidth, scaledHeight);
        }
    }

    public void renderHudPreview(float scaledWidth, float scaledHeight) {
        Render2DUtils.ensureInitialized();
        drawHudInfo(scaledWidth, scaledHeight);
    }

    // ==================== 调试信息绘制 ====================

    private void drawDebugInfo(float scaledWidth, float scaledHeight) {
        Minecraft mc = Minecraft.getInstance();
        List<ClientPlayerTickHandler> validHandlers = new ArrayList<>();
        int globalMaxTextWidth = MIN_COLUMN_WIDTH;

        // 1. 收集有效 Handler 并计算全局最大宽度
        for (ClientPlayerTickHandler handler : ClientPlayerTickManager.VALUES) {
            GuiBlockInfo guiInfo = handler.getCurrentRenderGuiBlockInfo();
            if (guiInfo == null) continue;

            validHandlers.add(handler);
            List<String> lines = buildHandlerDebugLines(handler, guiInfo);
            for (String line : lines) {
                String cleanLine = line.replaceAll("§[0-9a-fA-Fklmnor]", "");
                globalMaxTextWidth = Math.max(globalMaxTextWidth, mc.font.width(cleanLine));
            }
        }

        if (validHandlers.isEmpty()) return;

        // 2. 绘制公共信息（左上角）
        int commonInfoBottomY = drawCommonDebugInfo(SIDE_MARGIN, SIDE_MARGIN);

        // 3. 计算布局参数（动态适配屏幕）
        int columnWidth = globalMaxTextWidth + DEBUG_PADDING * 2;
        int maxColumnsPerSide = calculateMaxColumnsPerSide(scaledWidth, columnWidth);
        int availableHeight = (int) (scaledHeight - commonInfoBottomY - COMMON_INFO_OFFSET_Y - SIDE_MARGIN);

        // 4. 优先绘制左侧面板，再绘制右侧
        int drawnHandlers = drawHandlerPanels(
                validHandlers, 0,
                SIDE_MARGIN, commonInfoBottomY + COMMON_INFO_OFFSET_Y,
                columnWidth, maxColumnsPerSide, availableHeight,
                scaledHeight
        );

        // 如果左侧绘制不完，绘制右侧面板
        if (drawnHandlers < validHandlers.size()) {
            int rightStartX = (int) (scaledWidth - SIDE_MARGIN - columnWidth);
            drawHandlerPanels(
                    validHandlers, drawnHandlers,
                    rightStartX, commonInfoBottomY + COMMON_INFO_OFFSET_Y,
                    columnWidth, maxColumnsPerSide, availableHeight,
                    scaledHeight
            );
        }
    }

    private int calculateMaxColumnsPerSide(float scaledWidth, int columnWidth) {
        float centerAreaWidth = scaledWidth * 0.5f;
        float sideAvailableWidth = (scaledWidth - centerAreaWidth) / 2 - SIDE_MARGIN * 2;
        int maxColumns = Math.max(1, (int) (sideAvailableWidth / (columnWidth + COLUMN_SPACING)));
        return Math.min(maxColumns, 3);
    }

    private int drawHandlerPanels(List<ClientPlayerTickHandler> handlers, int startIndex,
                                  int startX, int startY, int columnWidth,
                                  int maxColumns, int availableHeight, float scaledHeight) {
        int drawnCount = 0;
        int currentColumn = 0;
        int currentX = startX;
        int currentY = startY;

        for (int i = startIndex; i < handlers.size(); i++) {
            ClientPlayerTickHandler handler = handlers.get(i);
            GuiBlockInfo guiInfo = handler.getCurrentRenderGuiBlockInfo();
            if (guiInfo == null) continue;

            List<String> debugLines = buildHandlerDebugLines(handler, guiInfo);
            int panelHeight = debugLines.size() * DEBUG_LINE_HEIGHT + DEBUG_PADDING * 2;

            if (currentColumn >= maxColumns) {
                currentColumn = 0;
                currentX = startX;
                currentY += panelHeight + DEBUG_PADDING * 2;

                if (currentY + panelHeight > scaledHeight - SIDE_MARGIN) {
                    break;
                }
            }

            // 绘制面板背景
            Render2DUtils.fill(
                    currentX, currentY,
                    currentX + columnWidth, currentY + panelHeight,
                    new Color(0, 0, 0, 50)
            );

            // 绘制文本行
            int lineY = currentY + DEBUG_PADDING;
            for (String line : debugLines) {
                drawDebugLine(line, currentX + DEBUG_PADDING, lineY);
                lineY += DEBUG_LINE_HEIGHT;
            }

            drawnCount++;
            currentColumn++;
            currentX += columnWidth + COLUMN_SPACING;

            if (currentY + panelHeight > scaledHeight - SIDE_MARGIN) {
                break;
            }
        }

        return drawnCount;
    }

    private int drawCommonDebugInfo(int startX, int startY) {
        List<String> commonLines = new ArrayList<>();
        commonLines.add("全局Tick: " + ClientPlayerTickManager.getCurrentHandlerTime());
        commonLines.add("活跃Handler数: " + ClientPlayerTickManager.VALUES.size());

        Minecraft mc = Minecraft.getInstance();
        int maxWidth = 0;
        for (String line : commonLines) {
            String cleanLine = line.replaceAll("§[0-9a-fA-Fklmnor]", "");
            maxWidth = Math.max(maxWidth, mc.font.width(cleanLine));
        }

        int bgWidth = maxWidth + DEBUG_PADDING * 2;
        int bgHeight = commonLines.size() * DEBUG_LINE_HEIGHT + DEBUG_PADDING * 2;

        Render2DUtils.fill(
                startX, startY,
                startX + bgWidth, startY + bgHeight,
                new Color(0, 0, 0, 50)
        );

        int lineY = startY + DEBUG_PADDING;
        for (String line : commonLines) {
            drawDebugLine(line, startX + DEBUG_PADDING, lineY);
            lineY += DEBUG_LINE_HEIGHT;
        }

        return startY + bgHeight;
    }

    private List<String> buildHandlerDebugLines(ClientPlayerTickHandler handler, GuiBlockInfo guiInfo) {
        List<String> lines = new ArrayList<>();
        lines.add("处理类型: " + handler.getId());
        lines.add("当前位置: " + guiInfo.pos.toShortString());
        if (guiInfo.requiredState != null) {
            lines.add("投影方块: " + guiInfo.requiredState.getBlock().getName().getString());
        }
        lines.add("当前方块: " + guiInfo.currentState.getBlock().getName().getString());
        lines.add("交互范围: " + booleanToColoredString(guiInfo.interacted));
        lines.add("选区类型: " + booleanToColoredString(guiInfo.posInSelectionRange));
        lines.add("已经执行: " + booleanToColoredString(guiInfo.execute));

        int renderIndex = handler.getRenderIndex();
        int queueSize = handler.getGuiBlockInfoQueueSize();
        lines.add("同刻迭代(GUI): " + formatAlignedNumber(renderIndex, queueSize) + "/" + queueSize);

        return lines;
    }

    private void drawDebugLine(String text, int x, int y) {
        Render2DUtils.drawString(text, x, y, new Color(0, 255, 255, 255), true);
    }

    // ==================== HUD 进度条等信息绘制 ====================

    private void drawHudInfo(float scaledWidth, float scaledHeight) {
        int centerX = (int) (scaledWidth / 2);
        int centerY = (int) (scaledHeight / 2);

        // 延迟过大警告
        if (Configs.Core.LAG_CHECK.getBooleanValue() &&
                ClientPlayerTickManager.getPacketTick() > Configs.Core.LAG_CHECK_MAX.getIntegerValue()) {
            Render2DUtils.drawString("延迟过大，已暂停运行", centerX, centerY - 22, Color.ORANGE, true, true);
        }

        int baseX = Configs.Core.RENDER_HUD_X.getIntegerValue();
        int baseY = Configs.Core.RENDER_HUD_Y.getIntegerValue();
        float hudScale = getHudScale();
        PanelLayout summaryLayout = computeHudPanelLayout(baseX, baseY, buildHudSummaryLines(), scaledWidth, scaledHeight, hudScale);
        int panelBottom = drawHudPanel(summaryLayout);
        PanelLayout modeLayout = computeHudPanelLayout(baseX, panelBottom + Math.max(4, Math.round(6 * hudScale)), buildHudModeLines(), scaledWidth, scaledHeight, hudScale);
        drawHudPanel(modeLayout);
    }

    private int drawHudPanel(PanelLayout layout) {
        if (layout.lines().isEmpty()) {
            return layout.drawY();
        }

        //#if MC >= 12111
        int padding = Math.max(1, Math.round(HUD_PADDING * layout.scale()));
        int lineStep = Math.max(1, Math.round(HUD_LINE_HEIGHT * layout.scale()));
        int panelHeight = Math.max(1, Math.round(layout.baseHeight() * layout.scale()));
        Render2DUtils.fill(
                layout.drawX(),
                layout.drawY(),
                layout.drawX() + layout.scaledWidth(),
                layout.drawY() + panelHeight,
                new Color(0, 0, 0, 110)
        );

        int textX = layout.drawX() + padding;
        int lineY = layout.drawY() + padding;
        for (HudLine line : layout.lines()) {
            Render2DUtils.drawStringScaled(line.text(), textX, lineY, line.color(), true, layout.scale());
            lineY += lineStep;
        }
        return layout.drawY() + panelHeight;
        //#else
        //$$ Render2DUtils.pushPose();
        //$$ Render2DUtils.translate(layout.drawX(), layout.drawY(), 0.0D);
        //$$ Render2DUtils.scale(layout.scale(), layout.scale(), 1.0F);
        //$$ Render2DUtils.fill(0, 0, layout.baseWidth(), layout.baseHeight(), new Color(0, 0, 0, 110));
        //$$
        //$$ int lineY = HUD_PADDING;
        //$$ for (HudLine line : layout.lines()) {
        //$$     Render2DUtils.drawString(line.text(), HUD_PADDING, lineY, line.color(), true);
        //$$     lineY += HUD_LINE_HEIGHT;
        //$$ }
        //$$ Render2DUtils.popPose();
        //$$ return layout.bottom();
        //#endif
    }

    private PanelLayout computeHudPanelLayout(int x, int y, List<HudLine> lines, float scaledWidth, float scaledHeight, float scale) {
        if (lines.isEmpty()) {
            return new PanelLayout(lines, x, y, 0, 0, 0, 0, scale);
        }

        Minecraft mc = Minecraft.getInstance();
        int maxWidth = 0;
        for (HudLine line : lines) {
            maxWidth = Math.max(maxWidth, mc.font.width(line.text()));
        }

        int baseWidth = maxWidth + HUD_PADDING * 2;
        int baseHeight = lines.size() * HUD_LINE_HEIGHT + HUD_PADDING * 2;
        //#if MC >= 12111
        int scaledWidthPixels = Math.max(1, Math.round(baseWidth * scale));
        int scaledHeightPixels = Math.max(1, Math.round(baseHeight * scale));
        //#else
        //$$ int scaledWidthPixels = Math.max(1, Math.round(baseWidth * scale));
        //$$ int scaledHeightPixels = Math.max(1, Math.round(baseHeight * scale));
        //#endif
        int drawX = Math.max(0, Math.min(x, (int) scaledWidth - scaledWidthPixels));
        int drawY = Math.max(0, Math.min(y, (int) scaledHeight - scaledHeightPixels));
        return new PanelLayout(lines, drawX, drawY, baseWidth, baseHeight, scaledWidthPixels, scaledHeightPixels, scale);
    }

    public HudBounds getHudBounds(float scaledWidth, float scaledHeight) {
        float hudScale = getHudScale();
        int baseX = Configs.Core.RENDER_HUD_X.getIntegerValue();
        int baseY = Configs.Core.RENDER_HUD_Y.getIntegerValue();
        PanelLayout summaryLayout = computeHudPanelLayout(baseX, baseY, buildHudSummaryLines(), scaledWidth, scaledHeight, hudScale);
        PanelLayout modeLayout = computeHudPanelLayout(baseX, summaryLayout.bottom() + Math.max(4, Math.round(6 * hudScale)), buildHudModeLines(), scaledWidth, scaledHeight, hudScale);

        if (summaryLayout.lines().isEmpty()) {
            return new HudBounds(modeLayout.drawX(), modeLayout.drawY(), modeLayout.scaledWidth(), modeLayout.scaledHeight());
        }
        if (modeLayout.lines().isEmpty()) {
            return new HudBounds(summaryLayout.drawX(), summaryLayout.drawY(), summaryLayout.scaledWidth(), summaryLayout.scaledHeight());
        }

        int minX = Math.min(summaryLayout.drawX(), modeLayout.drawX());
        int minY = Math.min(summaryLayout.drawY(), modeLayout.drawY());
        int maxX = Math.max(summaryLayout.right(), modeLayout.right());
        int maxY = Math.max(summaryLayout.bottom(), modeLayout.bottom());
        return new HudBounds(minX, minY, Math.max(0, maxX - minX), Math.max(0, maxY - minY));
    }

    private List<HudLine> buildHudSummaryLines() {
        List<HudLine> lines = new ArrayList<>();
        boolean enabled = ConfigUtils.isEnable();
        String workMode = ((WorkingModeType) Configs.Core.WORK_MODE.getOptionListValue()).equals(WorkingModeType.SINGLE) ? "单模" : "多模";
        lines.add(new HudLine("工作: " + (enabled ? "运行中" : "已关闭") + " | 模式: " + workMode + " | 功能: " + getActiveModeSummary(), new Color(255, 255, 255, 255)));

        String pauseReason = ClientPlayerTickManager.getLastPauseReason();
        if (!enabled) {
            lines.add(new HudLine("调度: 已关闭", new Color(255, 204, 102, 255)));
        } else if (pauseReason != null) {
            lines.add(new HudLine("调度: 暂停 | 原因: " + humanizeSchedulerReason(pauseReason), new Color(255, 180, 90, 255)));
        } else {
            lines.add(new HudLine("调度: 运行中 | Tick: " + ClientPlayerTickManager.getCurrentHandlerTime(), new Color(180, 255, 180, 255)));
        }
        return lines;
    }

    private List<HudLine> buildHudModeLines() {
        List<HudLine> lines = new ArrayList<>();
        appendCommonModeLines(lines, HudStatsManager.Mode.PRINT, getModeDisplayName(HudStatsManager.Mode.PRINT), ConfigUtils.isPrintMode());
        appendCommonModeLines(lines, HudStatsManager.Mode.MINE, getModeDisplayName(HudStatsManager.Mode.MINE), ConfigUtils.isMineMode());
        appendCommonModeLines(lines, HudStatsManager.Mode.FILL, getModeDisplayName(HudStatsManager.Mode.FILL), ConfigUtils.isFillMode());
        appendCommonModeLines(lines, HudStatsManager.Mode.FLUID, getModeDisplayName(HudStatsManager.Mode.FLUID), ConfigUtils.isFluidMode());
        appendBedrockLines(lines, ConfigUtils.isBedrockMode());
        return lines;
    }

    private void appendCommonModeLines(List<HudLine> lines, HudStatsManager.Mode mode, String label, boolean active) {
        if (!active) {
            return;
        }
        HudStatsManager.Snapshot snapshot = HudStatsManager.INSTANCE.snapshot(mode);
        String progressText = formatProgress(snapshot.finished(), snapshot.total(), snapshot.progress());
        String status = snapshot.lastReason();
        if (snapshot.total() <= 0 && snapshot.ratePerSecond() <= 0.0D && "运行中".equals(status)) {
            status = "无目标";
        }
        lines.add(new HudLine("[" + label + "] 进度 " + progressText + " | 速率 " + formatRate(snapshot.ratePerSecond()) + "/s | 累计 " + snapshot.lifetimeUnits(), new Color(120, 220, 255, 255)));

        String detail = "失败 " + snapshot.lifetimeFailures()
                + " | 延后 " + snapshot.lifetimeDeferred();
        if (mode == HudStatsManager.Mode.MINE) {
            detail += " | 重试 " + ClientPlayerTickManager.MINE.getRetryQueueSize();
        }
        detail += " | 状态 " + status;
        lines.add(new HudLine(detail, new Color(255, 255, 255, 255)));
    }

    private void appendBedrockLines(List<HudLine> lines, boolean active) {
        if (!active) {
            return;
        }
        HudStatsManager.Snapshot snapshot = HudStatsManager.INSTANCE.snapshot(HudStatsManager.Mode.BEDROCK);
        BedrockController.HudSnapshot bedrock = BedrockController.getHudSnapshot();
        String progressText = formatProgress(snapshot.finished(), snapshot.total(), snapshot.progress());
        int totalFailures = bedrock.failedTargets() + bedrock.stuckTargets();
        String status = humanizeBedrockReason(bedrock.lastReason());
        if (bedrock.totalTargets() <= 0 && snapshot.total() <= 0 && "运行中".equals(status)) {
            status = "无目标";
        }

        lines.add(new HudLine("[破基岩] 进度 " + progressText
                + " | 成功率 " + formatPercent(bedrock.successRate())
                + " | 成功速率 " + formatRate(snapshot.ratePerSecond()) + "/s", new Color(120, 255, 170, 255)));
        lines.add(new HudLine("成功 " + bedrock.confirmedSuccesses()
                + " | 失败 " + totalFailures
                + " | 活跃 " + bedrock.activeTargets() + "/" + bedrock.totalTargets()
                + " | 清理 " + bedrock.cleanupQueueSize()
                + " | 压力 " + bedrock.cleanupPressure(), new Color(255, 255, 255, 255)));
        lines.add(new HudLine("吞吐 " + bedrock.configuredThroughput()
                + " | 提交 " + bedrock.acceptedThisTick() + "/" + bedrock.submitCap()
                + " | 阻塞 " + bedrock.rejectedThisTick()
                + " | 状态 " + status, new Color(255, 255, 255, 255)));
    }

    private void drawProgressBar(int x, int y, int barWidth, int barHeight, double progress,
                                 Color bgColor, Color fgColor) {
        double clampedProgress = clamp(progress, 0.0, 1.0);
        int barXStart = x - (barWidth / 2);
        int barXEnd = x + (barWidth / 2);
        int barYEnd = y + barHeight;
        int filledWidth = (int) (clampedProgress * barWidth);

        Render2DUtils.fill(barXStart, y, barXEnd, barYEnd, bgColor);
        if (filledWidth > 0) {
            Render2DUtils.fill(barXStart, y, barXStart + filledWidth, barYEnd, fgColor);
        }
    }

    private String getActiveModeSummary() {
        if (!ConfigUtils.isEnable()) {
            return "无";
        }
        List<String> names = new ArrayList<>();
        if (ConfigUtils.isPrintMode()) {
            names.add(getModeDisplayName(HudStatsManager.Mode.PRINT));
        }
        if (ConfigUtils.isMineMode()) {
            names.add(getModeDisplayName(HudStatsManager.Mode.MINE));
        }
        if (ConfigUtils.isFillMode()) {
            names.add(getModeDisplayName(HudStatsManager.Mode.FILL));
        }
        if (ConfigUtils.isFluidMode()) {
            names.add(getModeDisplayName(HudStatsManager.Mode.FLUID));
        }
        if (ConfigUtils.isBedrockMode()) {
            names.add(getModeDisplayName(HudStatsManager.Mode.BEDROCK));
        }
        return names.isEmpty() ? "无" : String.join(", ", names);
    }

    private String getModeDisplayName(HudStatsManager.Mode mode) {
        return switch (mode) {
            case PRINT -> "打印";
            case MINE -> "挖掘";
            case FILL -> "填充";
            case FLUID -> "排流体";
            case BEDROCK -> "破基岩";
            case TOTAL -> "总计";
        };
    }

    private String formatProgress(long finished, long total, double progress) {
        if (total <= 0) {
            return "--";
        }
        return formatPercent(progress) + " (" + finished + "/" + total + ")";
    }

    private String formatRate(double rate) {
        return String.format("%.1f", rate);
    }

    private String formatPercent(double value) {
        return (int) Math.round(clamp(value, 0.0D, 1.0D) * 100.0D) + "%";
    }

    private float getHudScale() {
        return (float) clamp(Configs.Core.RENDER_HUD_SCALE.getIntegerValue() / 100.0D, 0.5D, 2.0D);
    }

    private String humanizeSchedulerReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "运行中";
        }
        if (reason.startsWith("shared_precheck")) {
            return "容器打开或切物品中";
        }
        if (reason.startsWith("handler_precheck")) {
            return "共享前置阻塞";
        }
        if (reason.startsWith("send_queue_wait_modify_look") || reason.startsWith("action_wait_modify_look")) {
            return "等待转头";
        }
        if (reason.startsWith("lag_check")) {
            return "延迟过大";
        }
        return reason;
    }

    private String humanizeBedrockReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "运行中";
        }
        return switch (reason) {
            case "idle" -> "空闲";
            case "running", "accepted" -> "运行中";
            case "startup_serial" -> "启动串行";
            case "accept_backpressure" -> "接受背压";
            case "submit_cap" -> "提交上限";
            case "active_cap" -> "活跃上限";
            case "retry_cooldown" -> "重试冷却";
            case "reserved_by_active_target" -> "被活跃任务占位";
            case "out_of_range_bedrock", "out_of_range_machine", "out_of_range" -> "超出交互范围";
            case "await_target_exposure" -> "等待目标暴露";
            case "duplicate_active_target" -> "重复目标";
            case "occupied_by_active_piston" -> "活塞占位";
            case "pending_cleanup" -> "等待清理";
            case "machine_overlap" -> "机器重叠";
            case "target_failed_on_create", "failed" -> "任务失败";
            case "stuck" -> "任务卡死";
            default -> reason;
        };
    }

    // ==================== 静态工具方法 ====================

    private static String booleanToColoredString(boolean value) {
        return value ? "§atrue" : "§cfalse";
    }

    private static String formatAlignedNumber(int current, int total) {
        int totalDigits = total == 0 ? 1 : String.valueOf(total).length();
        DecimalFormat formatter = new DecimalFormat(String.format("%0" + totalDigits + "d", 0));
        return formatter.format(current);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record HudLine(String text, Color color) {
    }

    private record PanelLayout(
            List<HudLine> lines,
            int drawX,
            int drawY,
            int baseWidth,
            int baseHeight,
            int scaledWidth,
            int scaledHeight,
            float scale
    ) {
        private int right() {
            return this.drawX + this.scaledWidth;
        }

        private int bottom() {
            return this.drawY + this.scaledHeight;
        }
    }

    public record HudBounds(int x, int y, int width, int height) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX <= this.x + this.width
                    && mouseY >= this.y && mouseY <= this.y + this.height;
        }
    }
}
