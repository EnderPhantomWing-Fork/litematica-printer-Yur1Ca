package me.aleksilassila.litematica.printer.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import me.aleksilassila.litematica.printer.utils.minecraft.StringUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#if MC >= 12111
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.TextAlignment;
//#endif
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

//#if MC >= 12106
import net.minecraft.client.renderer.RenderPipelines;
//#endif
//#if MC >= 12103 && MC < 12106
//$$ import net.minecraft.client.renderer.RenderType;
//#endif
//#if MC <= 11904
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import net.minecraft.client.gui.GuiComponent;
//#endif


import java.awt.*;
import java.lang.reflect.Method;

/**
 * 封装了原版 GUI 渲染的底层工具类，兼容多版本：
 * 1.18.x / 1.19.x / 1.20.x / 1.21.1 / 1.21.3+
 */
public class Render2DUtils {
    public static final Minecraft client = Minecraft.getInstance();
    private static PoseStack poseStack;
    private static GuiGraphicsExtractor guiGraphics;

    // ==================== 初始化 ====================

    public static void initMatrix(PoseStack poseStack) {
        Render2DUtils.poseStack = poseStack;
    }

    public static void initGuiGraphics(GuiGraphicsExtractor guiGraphics) {
        Render2DUtils.guiGraphics = guiGraphics;
    }

    public static void ensureInitialized() {
        //#if MC > 11904
        if (guiGraphics == null) {
            throw new NullPointerException("GuiGraphics is null! Call initGuiGraphics first.");
        }
        //#else
        //$$ if (poseStack == null) {
        //$$     throw new NullPointerException("PoseStack is null! Call initMatrix first.");
        //$$ }
        //#endif
    }

    // ==================== 文字与矩形 ====================

    public static void drawString(String text, int x, int y, Color color, boolean withShadow) {
        drawString(text, x, y, color, withShadow, false);
    }

    public static void drawString(String text, int x, int y, Color color, boolean withShadow, boolean centered) {
        if (centered) {
            x -= client.font.width(text) / 2;
        }
        ensureInitialized();

        //#if MC > 11904
        guiGraphics.text(client.font, text, x, y, color.getRGB(), withShadow);
        //#else
        //$$ if (withShadow) {
        //$$    client.font.drawShadow(poseStack, text, x, y, color.getRGB());
        //$$ } else {
        //$$    client.font.draw(poseStack, text, x, y, color.getRGB());
        //$$ }
        //#endif
    }

    public static void fill(int x1, int y1, int x2, int y2, Color color) {
        ensureInitialized();
        //#if MC > 11904
        guiGraphics.fill(x1, y1, x2, y2, color.getRGB());
        //#else
        //$$ GuiComponent.fill(poseStack, x1, y1, x2, y2, color.getRGB());
        //#endif
    }

    public static void drawStringScaled(String text, int x, int y, Color color, boolean withShadow, float scale) {
        //#if MC >= 12111
        ensureInitialized();
        if (Math.abs(scale - 1.0F) < 0.001F) {
            drawString(text, x, y, color, withShadow);
            return;
        }
        ActiveTextCollector collector = guiGraphics.textRenderer();
        ActiveTextCollector.Parameters parameters = collector.defaultParameters().withScale(scale);
        collector.accept(
                TextAlignment.LEFT,
                x,
                y,
                parameters,
                StringUtils.literal(text).withStyle(style -> style.withColor(color.getRGB()))
        );
        //#else
        //$$ drawString(text, x, y, color, withShadow);
        //#endif
    }

    public static void pushPose() {
        invokePoseMethod(new String[]{"pushPose", "pushMatrix"}, new Object[0]);
    }

    public static void popPose() {
        invokePoseMethod(new String[]{"popPose", "popMatrix"}, new Object[0]);
    }

    public static void translate(double x, double y, double z) {
        invokePoseMethod(
                new String[]{"translate"},
                new Object[]{x, y, z},
                new Object[]{x, y},
                new Object[]{(float) x, (float) y, (float) z},
                new Object[]{(float) x, (float) y}
        );
    }

    public static void scale(float x, float y, float z) {
        invokePoseMethod(
                new String[]{"scale"},
                new Object[]{x, y, z},
                new Object[]{x, y},
                new Object[]{x}
        );
    }

    private static void invokePoseMethod(String[] methodNames, Object[]... argumentVariants) {
        ensureInitialized();
        try {
            Object target = getPoseTarget();
            if (target == null) {
                return;
            }
            for (Method method : target.getClass().getMethods()) {
                if (!matchesMethodName(method.getName(), methodNames)) {
                    continue;
                }
                for (Object[] args : argumentVariants) {
                    if (method.getParameterCount() != args.length) {
                        continue;
                    }
                    Object[] convertedArgs = tryConvertArguments(method.getParameterTypes(), args);
                    if (convertedArgs != null) {
                        method.invoke(target, convertedArgs);
                        return;
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static boolean matchesMethodName(String methodName, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    private static Object[] tryConvertArguments(Class<?>[] parameterTypes, Object[] args) {
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            Class<?> type = parameterTypes[i];
            Object coerced = coerceArgument(type, value);
            if (coerced == null && value != null) {
                return null;
            }
            converted[i] = coerced;
        }
        return converted;
    }

    private static Object coerceArgument(Class<?> type, Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            return type.isInstance(value) || !type.isPrimitive() ? value : null;
        }
        if (type == float.class || type == Float.class) {
            return number.floatValue();
        }
        if (type == double.class || type == Double.class) {
            return number.doubleValue();
        }
        if (type == int.class || type == Integer.class) {
            return number.intValue();
        }
        if (type == long.class || type == Long.class) {
            return number.longValue();
        }
        return type.isInstance(value) ? value : null;
    }

    private static Object getPoseTarget() throws ReflectiveOperationException {
        //#if MC > 11904
        if (guiGraphics == null) {
            return null;
        }
        Method poseMethod = guiGraphics.getClass().getMethod("pose");
        return poseMethod.invoke(guiGraphics);
        //#else
        //$$ return poseStack;
        //#endif
    }

    // ==================== 纹理绘制 ====================

    /**
     * 绘制完整纹理（纹理文件整体宽高与绘制区域一致）
     */
    public static void drawTexture(Identifier texture, int x, int y, int width, int height) {
        drawTexture(texture, x, y, 0, 0, width, height, width, height);
    }

    /**
     * 绘制纹理的指定区域（支持 UV 裁剪）
     */
    public static void drawTexture(Identifier texture, int x, int y,
                                   int u, int v, int regionWidth, int regionHeight,
                                   int textureWidth, int textureHeight) {
        ensureInitialized();

        //#if MC >= 12106
        guiGraphics.blit(
                RenderPipelines.GUI_TEXTURED,
                texture,
                x, y,
                (float) u, (float) v,
                regionWidth, regionHeight,
                regionWidth, regionHeight,
                textureWidth, textureHeight
        );
        //#elseif MC >= 12103
        //$$ guiGraphics.blit(
        //$$         RenderType::guiTextured,
        //$$         texture,
        //$$         x, y,
        //$$         (float) u, (float) v,
        //$$         regionWidth, regionHeight,
        //$$         regionWidth, regionHeight,
        //$$         textureWidth, textureHeight
        //$$ );
        //#elseif MC > 11904
        //$$ guiGraphics.blit(texture, x, y, (float) u, (float) v, regionWidth, regionHeight, textureWidth, textureHeight);
        //#else
        //$$ RenderSystem.setShaderTexture(0, texture);
        //$$ GuiComponent.blit(poseStack, x, y, u, v, regionWidth, regionHeight, textureWidth, textureHeight);
        //#endif
    }

    // ==================== 物品绘制 ====================

    /**
     * 绘制物品图标（不依赖玩家实体，纯图标渲染）
     */
    public static void drawItem(ItemStack stack, int x, int y) {
        ensureInitialized();
        //#if MC > 12111
        guiGraphics.fakeItem(stack, x, y);
        //#elseif MC > 11904
        //$$ guiGraphics.renderFakeItem(stack, x, y);
        //#elseif MC > 11802
        //$$ client.getItemRenderer().renderGuiItem(poseStack, stack, x, y);
        //#else
        //$$ client.getItemRenderer().renderGuiItem(stack, x, y);
        //#endif
    }

    /**
     * 绘制物品图标及其装饰（数量、耐久条等）
     */
    public static void drawItemWithDecorations(ItemStack stack, int x, int y) {
        drawItem(stack, x, y);
        //#if MC > 12111
        guiGraphics.itemDecorations(client.font, stack, x, y);
        //#elseif MC > 11904
        //$$ guiGraphics.renderItemDecorations(client.font, stack, x, y);
        //#elseif MC > 11802
        //$$ client.getItemRenderer().renderGuiItemDecorations(poseStack, client.font, stack, x, y);
        //#else
        //$$ client.getItemRenderer().renderGuiItemDecorations(client.font, stack, x, y);
        //#endif
    }

    // ==================== 组合绘制 ====================

    /**
     * 物品图标 + 右侧文字
     */
    public static void drawItemWithLabel(ItemStack stack, int x, int y, String text, Color color, boolean shadow) {
        drawItem(stack, x, y);
        drawString(text, x + 20, y + 5, color, shadow);
    }

    /**
     * 纹理图标 + 右侧文字（可指定图标尺寸）
     */
    public static void drawIconWithLabel(Identifier texture, int x, int y,
                                         int iconWidth, int iconHeight,
                                         String text, Color color, boolean shadow) {
        drawTexture(texture, x, y, iconWidth, iconHeight);
        int textY = y + (iconHeight - client.font.lineHeight) / 2;
        drawString(text, x + iconWidth + 4, textY, color, shadow);
    }

    public static void drawBlock(Block block, int x, int y) {
        drawItem(block.asItem().getDefaultInstance(), x, y);
    }

    public static void drawBlockWithDecorations(Block block, int x, int y) {
        drawItemWithDecorations(block.asItem().getDefaultInstance(), x, y);
    }

    public static void drawBlockState(BlockState state, int x, int y) {
        drawBlock(state.getBlock(), x, y);
    }
}
