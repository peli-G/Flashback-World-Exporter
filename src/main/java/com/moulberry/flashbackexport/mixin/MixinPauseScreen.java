package com.moulberry.flashbackexport.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashbackexport.WorldExporter;
import net.minecraft.client.Minecraft;
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

    @Unique
    private Button fwe$exportButton = null;

    @Inject(method = "createPauseMenu", at = @At("RETURN"))
    public void fwe$addExportButton(CallbackInfo ci) {
        if (!Flashback.isInReplay()) return;
        if (fwe$buttonAdded) return;
        fwe$buttonAdded = true;

        fwe$exportButton = Button.builder(Component.literal(fwe$getLabel()), b -> {
            if (!WorldExporter.isExporting) {
                WorldExporter.startExport();
            }
        }).pos(4, this.height - 48).width(120).build();

        fwe$exportButton.setTooltip(Tooltip.create(Component.literal(
                "Save all loaded chunks to a singleplayer world"
        )));

        this.addRenderableWidget(fwe$exportButton);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    public void fwe$tick(CallbackInfo ci) {
        if (fwe$exportButton == null) return;
        fwe$exportButton.setMessage(Component.literal(fwe$getLabel()));
        fwe$exportButton.setTooltip(Tooltip.create(Component.literal(
                WorldExporter.isExporting ? WorldExporter.statusMessage : "Save all loaded chunks to a singleplayer world"
        )));
    }

    @Unique
    private String fwe$getLabel() {
        if (!WorldExporter.isExporting) return "Export World";
        if (WorldExporter.chunksTotal == 0) return "Exporting...";
        return "Exporting " + WorldExporter.chunksWritten + "/" + WorldExporter.chunksTotal;
    }
}
