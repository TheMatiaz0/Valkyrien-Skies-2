package org.valkyrienskies.mod.mixin.server.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Shadow
    public abstract void handleCustomPayload(final ClientboundCustomPayloadPacket packet);

    private static final Logger LOGGER = LogUtils.getLogger();

    @Unique
    private final Queue<ClientboundCustomPayloadPacket> vs$packetBuffer = new ConcurrentLinkedQueue<>();

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void safeHandleCustomPayload(final ClientboundCustomPayloadPacket packet, final CallbackInfo ci) {
        final Minecraft mc = Minecraft.getInstance();

        if (mc.player == null) {
            packet.getData().retain();
            vs$packetBuffer.add(packet);
            ci.cancel();
            return;
        }

        if (!vs$packetBuffer.isEmpty()) {
            if (mc.isSameThread()) {
                return;
            }
            mc.execute(this::flushPacketBuffer);
        }
    }

    @Unique
    private void flushPacketBuffer() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        ClientboundCustomPayloadPacket packet;
        while ((packet = vs$packetBuffer.poll()) != null) {
            try {
                this.handleCustomPayload(packet);
            } catch (final Exception e) {
                LOGGER.error("[VS2-Fix] Error handling buffered packet: " + packet.getIdentifier(), e);
            } finally {
                packet.getData().release();
            }
        }
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void clearBufferOnDisconnect(final Component reason, final CallbackInfo ci) {
        if (!vs$packetBuffer.isEmpty()) {
            LOGGER.info("[VS2-Fix] Clearing buffer due to disconnect.");
            ClientboundCustomPayloadPacket p;
            while ((p = vs$packetBuffer.poll()) != null) {
                p.getData().release();
            }
        }
    }
}
