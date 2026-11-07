package dev.swipixel.restyler;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** One % slider per mood. They mix; changes fade in over ~0.6 s, and the game keeps running behind the menu. */
public class MoodScreen extends Screen {

    private static final int CW = 150, BH = 20, GAP = 2;
    private final Screen parent;
    private int bottom = 0;

    public MoodScreen(Screen parent) {
        super(Text.literal("Moods"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int total = CW * 2 + 4;
        int x0 = (width - total) / 2;
        int y = 32;
        for (int i = 0; i < Mood.NAMES.length; i++) {           // two columns, so all moods fit on one screen
            final int k = i;
            int x = x0 + (i % 2) * (CW + 4);
            addDrawableChild(new RestylerScreen.Slider(x, y + (i / 2) * (BH + GAP), CW, BH, Mood.NAMES[i], Mood.get(i),
                    v -> Mood.set(k, (float) v)));
        }
        y += ((Mood.NAMES.length + 1) / 2) * (BH + GAP) + 4;
        addDrawableChild(new RestylerScreen.Slider(x0, y, CW, BH, "Brightness", Mood.brightness() / 2.0, 2.0,
                v -> Mood.setBrightness((float) (v * 2.0))));
        addDrawableChild(new RestylerScreen.Slider(x0 + CW + 4, y, CW, BH, "Torch / lava bloom", Mood.bloom(),
                v -> Mood.setBloom((float) v)));
        y += BH + GAP;
        addDrawableChild(new RestylerScreen.Slider(x0, y, CW, BH, "Sun rays", Mood.rays(),
                v -> Mood.setRays((float) v)));
        addDrawableChild(new RestylerScreen.Slider(x0 + CW + 4, y, CW, BH, "Torch shafts", Mood.torchRays(),
                v -> Mood.setTorchRays((float) v)));
        y += BH + GAP + 4;
        int tw = (total - 8) / 3;
        addDrawableChild(ButtonWidget.builder(Text.literal("Dark nights: " + (Mood.darkNights() ? "ON" : "OFF")), b -> {
            Mood.setDarkNights(!Mood.darkNights());
            b.setMessage(Text.literal("Dark nights: " + (Mood.darkNights() ? "ON" : "OFF")));
        }).dimensions(x0, y, tw, BH).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("All off"), b -> {
            Mood.allOff();
            clearAndInit();
        }).dimensions(x0 + tw + 4, y, tw, BH).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(x0 + 2 * (tw + 4), y, total - 2 * (tw + 4), BH).build());
        bottom = y + BH + 6;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = (width - (CW * 2 + 4)) / 2;
        context.fill(x - 8, 8, x + CW * 2 + 4 + 8, bottom, 0x88000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Moods"), width / 2, 12, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Keep the GAME brightness at 100%; set the mood here (0% = moody)."),
                width / 2, 22, 0xFFAAAAAA);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
