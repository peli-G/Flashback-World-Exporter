package com.moulberry.flashbackexport.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashbackexport.WorldExporter;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class MixinPauseScreen extends Screen {

    protected MixinPauseScreen(Component title) {
        super(title);
    }

    @Unique
    private boolean fwe$buttonAdded = false;

    @Inject(method = "createPauseMenu", at = @At("RETURN"))
    public void fwe$addExportButton(CallbackInfo ci) {
        if (!Flashback.isInReplay()) return;
        if (fwe$buttonAdded) return;
        fwe$buttonAdded = true;

        String label = WorldExporter.isExporting
                ? "Exporting... " + WorldExporter.chunksWritten + "/" + WorldExporter.chunksTotal
                : "Export World";

        Button btn = Button.builder(Component.literal(label), b -> {
            if (!WorldExporter.isExporting) {
                WorldExporter.startExport();
                b.setMessage(Component.literal("Exporting..."));
            }
        }).pos(4, this.height - 48).width(120).build();

        btn.setTooltip(Tooltip.create(Component.literal(
                WorldExporter.isExporting
                        ? WorldExporter.statusMessage
                        : "Save all loaded chunks to a singleplayer world"
        )));

        this.addRenderableWidget(btn);
    }
}
