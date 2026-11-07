package dev.swipixel.restyler;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** One % slider per effect, two columns. Effects are independent and stack with moods and styles. */
public class EffectScreen extends Screen {

    private static final int CW = 150, BH = 20, GAP = 4;
    private final Screen parent;
    private int bottom = 0;

    public EffectScreen(Screen parent) {
        super(Text.literal("Effects"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int total = CW * 2 + GAP;
        int x0 = (width - total) / 2;
        int y0 = 34;
        int rows = (Effect.NAMES.length + 1) / 2;
        for (int i = 0; i < Effect.NAMES.length; i++) {
            final int k = i;
            int x = x0 + (i % 2) * (CW + GAP), y = y0 + (i / 2) * (BH + GAP);
            addDrawableChild(new RestylerScreen.Slider(x, y, CW, BH, Effect.NAMES[i], Effect.get(i),
                    v -> Effect.set(k, (float) v)));
        }
        int y = y0 + rows * (BH + GAP) + 6;
        addDrawableChild(ButtonWidget.builder(Text.literal("All off"), b -> {
            Effect.allOff();
            clearAndInit();
        }).dimensions(x0, y, CW, BH).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(x0 + CW + GAP, y, CW, BH).build());
        bottom = y + BH + 8;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = (width - (CW * 2 + GAP)) / 2;
        context.fill(x - 8, 8, x + CW * 2 + GAP + 8, bottom, 0x88000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Effects"), width / 2, 12, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Built from the depth: they stack with moods and styles. Heavier on the GPU."),
                width / 2, 22, 0xFFAAAAAA);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
