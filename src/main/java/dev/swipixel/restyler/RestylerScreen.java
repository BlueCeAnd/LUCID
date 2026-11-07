package dev.swipixel.restyler;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.DoubleConsumer;

/** Menu (key O). The game keeps running behind it, so every change is visible immediately. */
public class RestylerScreen extends Screen {

    private static final int BW = 200;
    private static final int BH = 20;
    private static final int GAP = 4;

    private final Restyler r = Restyler.INSTANCE;

    public RestylerScreen() {
        super(Text.literal("Restyler"));
    }

    @Override
    protected void init() {
        int x = (width - BW) / 2;
        int y = 34;

        int tw = (BW - 2 * GAP) / 3;
        addDrawableChild(ButtonWidget.builder(Text.literal("Moods"), b ->
                client.setScreen(new MoodScreen(this))).dimensions(x, y, tw, BH).tooltip(Tooltip.of(Text.literal(
                "Now: " + Mood.summary() + "\n"
                        + "Dark fantasy, aurora night, gothic, apocalyptic, horror, golden hour, cozy, dreamy, cinematic, vibrant, "
                        + "misty morning, neon night, frozen, stormy - each with its own % slider, mixable.\n"
                        + "Drawn with the real depth (fog grows with distance)."))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Styles"), b ->
                client.setScreen(new StyleScreen(this))).dimensions(x + tw + GAP, y, tw, BH).tooltip(Tooltip.of(Text.literal(
                "Now: " + Style.summary() + "\n"
                        + "Anime, pastel, oil paint, watercolor, comic, pencil sketch, noir, old film, vaporwave, retro green - "
                        + "mixable % sliders. Pixelate and CRT / VHS stack on top of any style."))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Effects"), b ->
                client.setScreen(new EffectScreen(this))).dimensions(x + 2 * (tw + GAP), y, BW - 2 * (tw + GAP), BH).tooltip(Tooltip.of(Text.literal(
                "Now: " + Effect.summary() + "\n"
                        + "Wet puddles with real reflections, snow cover, overgrown ruins, realistic light (sun shadows, "
                        + "ambient occlusion, haze), depth of field, tilt-shift. Built from the depth buffer."))).build());
        y += BH + GAP;
        addDrawableChild(ButtonWidget.builder(onOff("Restyler", r.isEnabled()), b -> {
            r.setEnabled(!r.isEnabled());
            b.setMessage(onOff("Restyler", r.isEnabled()));
        }).dimensions(x, y, BW, BH).tooltip(Tooltip.of(Text.literal(
                "Turn all looks on or off (key K). Tip: /vibe <words> picks a mix for you."))).build());
        y += BH + GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("World looks"), b ->
                client.setScreen(new WorldScreen(this))).dimensions(x, y, BW, BH).tooltip(Tooltip.of(Text.literal(
                WorldLook.INSTANCE.statusTag() + "\n"
                        + "Looks per biome, time of day, weather and placed zones. Your own sliders always win"
                        + " (pinned) until you turn world looks on again (/vibe auto)."))).build());
        y += BH + GAP + 6;
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(x, y, BW, BH).build());
        bottom = y + BH + 8;
    }

    private int bottom = 0;

    private static Text onOff(String name, boolean on) {
        return Text.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // no blur: the styled world stays visible behind the menu
        int x = (width - BW) / 2;
        context.fill(x - 8, 8, x + BW + 8, bottom, 0x88000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Restyler"), width / 2, 12, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(r.statusLine()), width / 2, 22, 0xFFAAAAAA);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    static final class Slider extends SliderWidget {
        private final String label;
        private final DoubleConsumer onChange;
        private final double scale;    // shown % = value * 100 * scale

        Slider(int x, int y, int w, int h, String label, double value, DoubleConsumer onChange) {
            this(x, y, w, h, label, value, 1.0, onChange);
        }

        Slider(int x, int y, int w, int h, String label, double value, double scale, DoubleConsumer onChange) {
            super(x, y, w, h, Text.empty(), value);
            this.label = label;
            this.onChange = onChange;
            this.scale = scale;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(label + ": " + Math.round(value * 100 * scale) + "%"));
        }

        @Override
        protected void applyValue() {
            onChange.accept(value);
        }
    }
}
