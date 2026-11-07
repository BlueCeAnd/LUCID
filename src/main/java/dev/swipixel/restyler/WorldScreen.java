package dev.swipixel.restyler;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** World looks: layer toggles, variation, fade time, pin / unpin, and what the world look is right now. */
public class WorldScreen extends Screen {

    private static final int CW = 150, BH = 20, GAP = 4;
    private final Screen parent;
    private final WorldLook w = WorldLook.INSTANCE;
    private int bottom = 0, statusY = 0;

    public WorldScreen(Screen parent) {
        super(Text.literal("World looks"));
        this.parent = parent;
    }

    private static Text onOff(String name, boolean on) {
        return Text.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    @Override
    protected void init() {
        int total = CW * 2 + GAP;
        int x0 = (width - total) / 2;
        int y = 34;
        addDrawableChild(ButtonWidget.builder(onOff("World looks", !w.pinned()), b -> {
            w.setPinned(!w.pinned());
            b.setMessage(onOff("World looks", !w.pinned()));
        }).dimensions(x0, y, total, BH).tooltip(Tooltip.of(Text.literal(
                "ON: biomes, time, weather and zones drive the look (/vibe auto).\n"
                        + "OFF: your own sliders win (moving any slider or /vibe <text> also turns this off)."))).build());
        y += BH + GAP;
        addDrawableChild(ButtonWidget.builder(onOff("Biomes", w.biomes()), b -> {
            w.setBiomes(!w.biomes());
            b.setMessage(onOff("Biomes", w.biomes()));
        }).dimensions(x0, y, CW, BH).tooltip(Tooltip.of(Text.literal(
                "Each biome's base look (biomes.txt), blended over ~50 blocks at borders, plus its variation."))).build());
        addDrawableChild(ButtonWidget.builder(onOff("Time & weather", w.timeWeather()), b -> {
            w.setTimeWeather(!w.timeWeather());
            b.setMessage(onOff("Time & weather", w.timeWeather()));
        }).dimensions(x0 + CW + GAP, y, CW, BH).tooltip(Tooltip.of(Text.literal(
                "@dawn / @noon / @dusk / @night / @rain / @snow / @thunder lines. They fade out where you can't see the sky."))).build());
        y += BH + GAP;
        addDrawableChild(ButtonWidget.builder(onOff("Zones", w.zonesOn()), b -> {
            w.setZonesOn(!w.zonesOn());
            b.setMessage(onOff("Zones", w.zonesOn()));
        }).dimensions(x0, y, CW, BH).tooltip(Tooltip.of(Text.literal(
                "Areas you place with /vibe zone add | box (stored per world)."))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reload files"), b -> {
            w.reload();
            Restyler.say("World looks: files reloaded", true);
        }).dimensions(x0 + CW + GAP, y, CW, BH).tooltip(Tooltip.of(Text.literal(
                "Reads world.properties, biomes.txt and this world's zones again (/vibe world reload)."))).build());
        y += BH + GAP;
        addDrawableChild(new RestylerScreen.Slider(x0, y, CW, BH, "Variation", w.variation() / 2.0, 2.0,
                v -> w.setVariation((float) (v * 2.0))));
        addDrawableChild(new FadeSlider(x0 + CW + GAP, y, CW, BH, w.fadeSeconds() / 20.0));
        y += BH + GAP + 4;
        statusY = y;
        y += 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(x0, y, total, BH).build());
        bottom = y + BH + 8;
    }

    /** 0..20 s. */
    private final class FadeSlider extends net.minecraft.client.gui.widget.SliderWidget {
        FadeSlider(int x, int y, int ww, int h, double value) {
            super(x, y, ww, h, Text.empty(), value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal("Fade: " + Math.round(value * 20) + " s"));
        }

        @Override
        protected void applyValue() {
            w.setFadeSeconds((float) Math.round(value * 20));
        }
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
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("World looks"), width / 2, 12, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(w.statusTag()), width / 2, 22, 0xFFAAAAAA);
        int maxW = CW * 2 + GAP;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(fit(w.biomeStatus(), maxW)), width / 2, statusY, 0xFFDDDDDD);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(fit(w.lookSummary(), maxW)), width / 2, statusY + 11, 0xFFDDDDDD);
    }

    private String fit(String s, int maxW) {
        if (textRenderer.getWidth(s) <= maxW) return s;
        return textRenderer.trimToWidth(s, maxW - textRenderer.getWidth("...")) + "...";
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
