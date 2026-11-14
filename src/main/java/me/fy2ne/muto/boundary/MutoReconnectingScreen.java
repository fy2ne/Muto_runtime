package me.fy2ne.muto.boundary;

import me.fy2ne.muto.MutoLog;
import me.fy2ne.muto.engine.ReloadEngine;
import me.fy2ne.muto.engine.ReloadResult;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MutoReconnectingScreen extends Screen {
    private final Screen origin;
    private final Path modsDir;
    private final ServerData targetServer;
    private final String singleplayerLevelId;

    private volatile String statusText = "Preparing hot reload...";
    private volatile float progress = 0.05f;
    private float renderedProgress = 0.05f;
    private final AtomicBoolean reloadStarted = new AtomicBoolean(false);

    public MutoReconnectingScreen(Screen origin, Path modsDir, ServerData targetServer, String singleplayerLevelId) {
        super(Component.literal("Hot Reload"));
        this.origin = origin;
        this.modsDir = modsDir;
        this.targetServer = targetServer;
        this.singleplayerLevelId = singleplayerLevelId;
    }

    @Override
    protected void init() {
        int btnW = 200;
        int btnH = 20;
        int btnX = (this.width - btnW) / 2;
        int btnY = this.height / 4 + 120 + 12;

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_CANCEL, btn -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(new TitleScreen());
                    }
                }).bounds(btnX, btnY, btnW, btnH).build()
        );

        if (reloadStarted.compareAndSet(false, true)) {
            startReload();
        }
    }

    private void startReload() {
        ReloadEngine.INSTANCE.reloadAsync(modsDir, msg -> {
            this.statusText = msg;
            if (msg.startsWith("Scanning")) progress = 0.20f;
            else if (msg.startsWith("Injecting") || msg.startsWith("Registering")) progress = 0.45f;
            else if (msg.startsWith("Tearing") || msg.startsWith("Unfreez")) progress = 0.70f;
            else if (msg.startsWith("Invoking")) progress = 0.85f;
            else if (msg.startsWith("Finaliz") || msg.startsWith("Mounting")) progress = 0.95f;
        }).thenAccept(res -> {
            progress = 1.0f;
            if (this.minecraft != null) {
                this.minecraft.execute(() -> onFinish(res));
            }
        });
    }

    private void onFinish(ReloadResult res) {
        if (this.minecraft == null) return;

        if (targetServer != null) {
            this.statusText = "Reconnecting to " + targetServer.name + "...";
            try {
                ServerAddress addr = ServerAddress.parseString(targetServer.ip);
                ConnectScreen.startConnecting(new TitleScreen(), this.minecraft, addr, targetServer, false, null);
                return;
            } catch (Throwable t) {
                MutoLog.error("auto-reconnect failed: {}", t.getMessage(), t);
            }
        }

        if (singleplayerLevelId != null && !singleplayerLevelId.isBlank()) {
            this.statusText = "Rejoining world " + singleplayerLevelId + "...";
            try {
                this.minecraft.createWorldOpenFlows().openWorld(
                        singleplayerLevelId,
                        () -> this.minecraft.setScreenAndShow(new TitleScreen())
                );
                return;
            } catch (Throwable t) {
                MutoLog.error("singleplayer auto-rejoin failed: {}", t.getMessage(), t);
            }
        }

        if (origin != null && !(origin instanceof MutoReconnectingScreen)) {
            this.minecraft.setScreenAndShow(origin);
        } else {
            this.minecraft.setScreenAndShow(new TitleScreen());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        renderedProgress += (progress - renderedProgress) * 0.15f;

        String hdr = (targetServer != null || singleplayerLevelId != null)
                ? "Reloading & Rejoining"
                : "Applying Mod Changes";
        graphics.centeredText(this.font, Component.literal("§f§l" + hdr), this.width / 2, this.height / 2 - 46, 0xFFFFFF);

        if (targetServer != null) {
            graphics.centeredText(this.font, Component.literal("§7Server: " + targetServer.ip), this.width / 2, this.height / 2 - 30, 0xAAAAAA);
        } else if (singleplayerLevelId != null) {
            graphics.centeredText(this.font, Component.literal("§7World: " + singleplayerLevelId), this.width / 2, this.height / 2 - 30, 0xAAAAAA);
        }

        graphics.centeredText(this.font, Component.literal("§e" + statusText), this.width / 2, this.height / 2 - 12, 0xFFFF55);

        int barW = 200;
        int barH = 5;
        int barX = (this.width - barW) / 2;
        int barY = this.height / 2 + 8;

        graphics.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF404040);
        graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF101010);
        int fillW = Math.max(2, (int) (barW * Math.min(1.0f, renderedProgress)));
        graphics.fill(barX, barY, barX + fillW, barY + barH, 0xFF80FF20);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
