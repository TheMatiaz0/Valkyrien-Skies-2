package org.valkyrienskies.mod.mixin.server.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Unique
    private static final String[] SYSTEM_NAMESPACES = {
        "minecraft",
        "fabric",
        "forge",
        "neoforge"
    };

    @Shadow
    public abstract void handleCustomPayload(final ClientboundCustomPayloadPacket packet);

    @Unique
    private static final Logger LOGGER = LogManager.getLogger("VS2 Network Mixin");

    @Unique
    private final ThreadLocal<Boolean> vs$isGuarded = ThreadLocal.withInitial(() -> false);

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void safeHandleCustomPayload(final ClientboundCustomPayloadPacket packet, final CallbackInfo ci) {
        if (vs$isGuarded.get()) return;

        final Minecraft mc = Minecraft.getInstance();

        if (mc.player == null) {
            final ResourceLocation id = packet.getIdentifier();

            if (!isSystemPacket(id.getNamespace())) {
                ci.cancel();
                attemptPacketExecution(packet, mc);
            }
        }
    }

    @Unique
    private boolean isSystemPacket(final String namespace) {
        for (final String systemId : SYSTEM_NAMESPACES) {
            if (namespace.equals(systemId)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private void attemptPacketExecution(final ClientboundCustomPayloadPacket packet, final Minecraft mc) {
        packet.getData().markReaderIndex();
        packet.getData().retain();

        try {
            vs$isGuarded.set(true);
            this.handleCustomPayload(packet);
            packet.getData().release();

        } catch (final NullPointerException e) {
            packet.getData().resetReaderIndex();
            scheduleRetry(packet, mc);

        } catch (final Exception e) {
            LOGGER.error("Packet failed permanently: " + packet.getIdentifier(), e);
            packet.getData().release();
        } finally {
            vs$isGuarded.set(false);
        }
    }

    @Unique
    private void scheduleRetry(final ClientboundCustomPayloadPacket packet, final Minecraft mc) {
        mc.execute(() -> {
            if (mc.player != null) {
                try {
                    this.handleCustomPayload(packet);
                } catch (final Exception e) {
                    LOGGER.error("Error handling deferred packet: " + packet.getIdentifier(), e);
                } finally {
                    packet.getData().release();
                }
            } else {
                scheduleRetry(packet, mc);
            }
        });
    }
}
