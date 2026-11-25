package caffeinated_mobs;

import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

final class ClientSetup {
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CaffeinatedMobs.VACANCY.get(), NoopRenderer::new);
    }

    private ClientSetup() {}
}
