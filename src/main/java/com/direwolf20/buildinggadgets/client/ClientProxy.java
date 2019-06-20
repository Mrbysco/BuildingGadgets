package com.direwolf20.buildinggadgets.client;

import afu.org.checkerframework.checker.nullness.qual.Nullable;
import com.direwolf20.buildinggadgets.client.events.EventClientTick;
import com.direwolf20.buildinggadgets.client.events.EventTooltip;
import com.direwolf20.buildinggadgets.common.blocks.ConstructionBlockTileEntity;
import com.direwolf20.buildinggadgets.common.blocks.templatemanager.TemplateManagerGUI;
import com.direwolf20.buildinggadgets.common.registry.objects.BGContainers;
import com.direwolf20.buildinggadgets.common.util.ref.Reference;
import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.gui.ScreenManager;
import net.minecraft.client.renderer.model.BakedQuad;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.model.ItemOverrideList;
import net.minecraft.client.renderer.model.ModelResourceLocation;
import net.minecraft.client.renderer.texture.MissingTextureSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Direction;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IEnviromentBlockReader;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.client.model.data.IDynamicBakedModel;
import net.minecraftforge.client.model.data.IModelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DeferredWorkQueue;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Random;

@EventBusSubscriber(Dist.CLIENT)
public class ClientProxy {

    public static void clientSetup(final IEventBus eventBus) {
        DeferredWorkQueue.runLater(KeyBindings::init);
        //BuildingObjects.initColorHandlers();
        //eventBus.addListener(ClientProxy::renderWorldLastEvent);
        MinecraftForge.EVENT_BUS.addListener(ClientProxy::bakeModels);
        MinecraftForge.EVENT_BUS.addListener(EventClientTick::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(EventTooltip::onDrawTooltip);
        ScreenManager.registerFactory(BGContainers.TEMPLATE_MANAGER_CONTAINER, TemplateManagerGUI::new);
    }

    private static void bakeModels(ModelBakeEvent event) {
        event.getModelRegistry().put(new ModelResourceLocation(Reference.BlockReference.CONSTRUCTION_BLOCK), new IDynamicBakedModel() {
            @Override
            public boolean isGui3d() {
                return false;
            }

            @Override
            public boolean isBuiltInRenderer() {
                return false;
            }

            @Override
            public boolean isAmbientOcclusion() {
                return true;
            }

            @Override
            public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, Random rand, IModelData modelData) {
                IBakedModel model;
                BlockState facadeState = modelData.getData(ConstructionBlockTileEntity.FACADE_STATE);
                model = Minecraft.getInstance().getBlockRendererDispatcher().getBlockModelShapes().getModel(facadeState);
                return model.getQuads(facadeState, side, rand);

            }

            @Override
            public TextureAtlasSprite getParticleTexture() {
                return MissingTextureSprite.func_217790_a();
            }

            @Override
            public ItemOverrideList getOverrides() {
                return null;
            }

            @Override
            @Nonnull
            public IModelData getModelData(@Nonnull IEnviromentBlockReader world, @Nonnull BlockPos pos, @Nonnull BlockState state, @Nonnull IModelData tileData) {
                return tileData;
            }
        });
    }

    @SubscribeEvent
    public static void registerSprites(TextureStitchEvent.Pre event) {
        //registerSprite(event, TemplateManagerContainer.TEXTURE_LOC_SLOT_TOOL);
        //registerSprite(event, TemplateManagerContainer.TEXTURE_LOC_SLOT_TEMPLATE);
    }

    private static void registerSprite(TextureStitchEvent.Pre event, String loc) {
        //TODO replace with something that doesn't result in an StackOverflow error
        //event.getMap().func_215254_a(Minecraft.getInstance().getResourceManager(), Collections.singleton(new ResourceLocation(loc)), null);
    }

    public static void playSound(SoundEvent sound, float pitch) {
        Minecraft.getInstance().getSoundHandler().play(SimpleSound.master(sound, pitch));
    }
}
