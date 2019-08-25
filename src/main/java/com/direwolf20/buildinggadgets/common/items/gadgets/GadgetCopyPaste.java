package com.direwolf20.buildinggadgets.common.items.gadgets;

import com.direwolf20.buildinggadgets.api.building.Region;
import com.direwolf20.buildinggadgets.api.building.view.*;
import com.direwolf20.buildinggadgets.api.capability.CapabilityTemplate;
import com.direwolf20.buildinggadgets.api.exceptions.TransactionExecutionException;
import com.direwolf20.buildinggadgets.api.template.IBuildOpenOptions;
import com.direwolf20.buildinggadgets.api.template.IBuildOpenOptions.OpenType;
import com.direwolf20.buildinggadgets.api.template.ITemplate;
import com.direwolf20.buildinggadgets.api.template.SimpleBuildOpenOptions;
import com.direwolf20.buildinggadgets.api.template.transaction.ITemplateTransaction;
import com.direwolf20.buildinggadgets.api.template.transaction.TemplateTransactions;
import com.direwolf20.buildinggadgets.client.events.EventTooltip;
import com.direwolf20.buildinggadgets.client.gui.GuiMod;
import com.direwolf20.buildinggadgets.common.BuildingGadgets;
import com.direwolf20.buildinggadgets.common.blocks.EffectBlock;
import com.direwolf20.buildinggadgets.common.blocks.EffectBlock.Mode;
import com.direwolf20.buildinggadgets.common.capability.DelegatingTemplateProvider;
import com.direwolf20.buildinggadgets.common.commands.CopyUnloadedCommand;
import com.direwolf20.buildinggadgets.common.concurrent.CopyScheduler;
import com.direwolf20.buildinggadgets.common.concurrent.PlacementScheduler;
import com.direwolf20.buildinggadgets.common.concurrent.ServerTickingScheduler;
import com.direwolf20.buildinggadgets.common.concurrent.TimeOutSupplier;
import com.direwolf20.buildinggadgets.common.config.Config;
import com.direwolf20.buildinggadgets.common.items.gadgets.renderers.BaseRenderer;
import com.direwolf20.buildinggadgets.common.items.gadgets.renderers.CopyPasteRender;
import com.direwolf20.buildinggadgets.common.network.PacketHandler;
import com.direwolf20.buildinggadgets.common.network.packets.PacketBindTool;
import com.direwolf20.buildinggadgets.common.util.GadgetUtils;
import com.direwolf20.buildinggadgets.common.util.helpers.NBTHelper;
import com.direwolf20.buildinggadgets.common.util.helpers.VectorHelper;
import com.direwolf20.buildinggadgets.common.util.lang.MessageTranslation;
import com.direwolf20.buildinggadgets.common.util.lang.Styles;
import com.direwolf20.buildinggadgets.common.util.lang.TooltipTranslation;
import com.direwolf20.buildinggadgets.common.util.ref.NBTKeys;
import com.direwolf20.buildinggadgets.common.util.tools.NetworkIO;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSortedSet;
import it.unimi.dsi.fastutil.bytes.Byte2ObjectMap;
import it.unimi.dsi.fastutil.bytes.Byte2ObjectOpenHashMap;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.Constants.NBT;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class GadgetCopyPaste extends AbstractGadget {

    public enum ToolMode {
        COPY(0),
        PASTE(1);
        public static final ToolMode[] VALUES = values();
        private static final Byte2ObjectMap<ToolMode> BY_ID;

        static {
            BY_ID = new Byte2ObjectOpenHashMap<>();
            for (ToolMode mode : VALUES) {
                assert ! BY_ID.containsKey(mode.getId());
                BY_ID.put(mode.getId(), mode);
            }
        }

        private final byte id;

        ToolMode(int id) {
            this.id = (byte) id;
        }

        public byte getId() {
            return id;
        }

        public ToolMode next() {
            return VALUES[(this.ordinal() + 1) % VALUES.length];
        }

        @Nullable
        public static ToolMode ofId(byte id) {
            return BY_ID.get(id);
        }
    }

    public static final int TRANSACTION_CREATION_LIMIT = 20; //try for one second

    private static final Joiner CHUNK_JOINER = Joiner.on("; ");

    public GadgetCopyPaste(Properties builder) {
        super(builder);
    }

    @Override
    public int getEnergyMax() {
        return Config.GADGETS.GADGET_COPY_PASTE.maxEnergy.get();
    }

    @Override
    public int getEnergyCost(ItemStack tool) {
        return Config.GADGETS.GADGET_COPY_PASTE.energyCost.get();
    }

    @Override
    protected Supplier<BaseRenderer> createRenderFactory() {
        return CopyPasteRender::new;
    }

    @Override
    protected void addCapabilityProviders(Builder<ICapabilityProvider> providerBuilder, ItemStack stack, @Nullable CompoundNBT tag) {
        super.addCapabilityProviders(providerBuilder, stack, tag);
        providerBuilder.add(new DelegatingTemplateProvider());
    }

    public static int getCopyCounter(ItemStack stack) {
        CompoundNBT nbt = NBTHelper.getOrNewTag(stack);
        return nbt.getInt(NBTKeys.TEMPLATE_COPY_COUNT); //returns 0 if not present
    }

    public static int getAndIncrementCopyCounter(ItemStack stack) {
        CompoundNBT nbt = NBTHelper.getOrNewTag(stack);
        int count = nbt.getInt(NBTKeys.TEMPLATE_COPY_COUNT); //returns 0 if not present
        nbt.putInt(NBTKeys.TEMPLATE_COPY_COUNT, count + 1);
        return count;
    }

    private static void setAnchor(ItemStack stack, BlockPos anchorPos) {
        GadgetUtils.writePOSToNBT(stack, anchorPos, NBTKeys.GADGET_ANCHOR);
    }

    public static BlockPos getAnchor(ItemStack stack) {
        return GadgetUtils.getPOSFromNBT(stack, NBTKeys.GADGET_ANCHOR);
    }

    public static Optional<Region> getSelectedRegion(ItemStack stack) {
        BlockPos lower = getLowerRegionBound(stack);
        BlockPos upper = getUpperRegionBound(stack);
        if (lower != null && upper != null) {
            return Optional.of(new Region(lower, upper));
        }
        return Optional.empty();
    }

    public static void setSelectedRegion(ItemStack stack, @Nullable Region region) {
        if (region != null) {
            setLowerRegionBound(stack, region.getMin());
            setUpperRegionBound(stack, region.getMax());
        } else {
            setLowerRegionBound(stack, null);
            setUpperRegionBound(stack, null);
        }
    }

    public static void setUpperRegionBound(ItemStack stack, @Nullable BlockPos pos) {
        CompoundNBT nbt = NBTHelper.getOrNewTag(stack);
        if (pos != null)
            nbt.put(NBTKeys.GADGET_START_POS, NBTUtil.writeBlockPos(pos));
        else
            nbt.remove(NBTKeys.GADGET_START_POS);
    }

    public static void setLowerRegionBound(ItemStack stack, @Nullable BlockPos pos) {
        CompoundNBT nbt = NBTHelper.getOrNewTag(stack);
        if (pos != null)
            nbt.put(NBTKeys.GADGET_END_POS, NBTUtil.writeBlockPos(pos));
        else
            nbt.remove(NBTKeys.GADGET_END_POS);
    }

    @Nullable
    public static BlockPos getUpperRegionBound(ItemStack stack) {
        CompoundNBT nbt = NBTHelper.getOrNewTag(stack);
        if (nbt.contains(NBTKeys.GADGET_START_POS, NBT.TAG_COMPOUND))
            return NBTUtil.readBlockPos(nbt.getCompound(NBTKeys.GADGET_START_POS));
        return null;
    }

    @Nullable
    public static BlockPos getLowerRegionBound(ItemStack stack) {
        CompoundNBT nbt = NBTHelper.getOrNewTag(stack);
        if (nbt.contains(NBTKeys.GADGET_END_POS, NBT.TAG_COMPOUND))
            return NBTUtil.readBlockPos(nbt.getCompound(NBTKeys.GADGET_END_POS));
        return null;
    }

    private static void setLastBuild(ItemStack stack, BlockPos anchorPos, DimensionType dim) {
        CompoundNBT nbt = NBTHelper.getOrNewTag(stack);
        nbt.put(NBTKeys.GADGET_LAST_BUILD_POS, NBTUtil.writeBlockPos(anchorPos));
        assert dim.getRegistryName() != null;
        nbt.putString(NBTKeys.GADGET_LAST_BUILD_DIM, dim.getRegistryName().toString());
    }

    private static BlockPos getLastBuild(ItemStack stack) {
        return GadgetUtils.getPOSFromNBT(stack, NBTKeys.GADGET_LAST_BUILD_POS);
    }

    @Nullable
    private static ResourceLocation getLastBuildDim(ItemStack stack) {
        return GadgetUtils.getDIMFromNBT(stack, NBTKeys.GADGET_LAST_BUILD_DIM);
    }

    private static void setToolMode(ItemStack stack, ToolMode mode) {
        CompoundNBT tagCompound = NBTHelper.getOrNewTag(stack);
        tagCompound.putByte(NBTKeys.GADGET_MODE, mode.getId());
    }

    public static ToolMode getToolMode(ItemStack stack) {
        CompoundNBT tagCompound = NBTHelper.getOrNewTag(stack);
        ToolMode mode = ToolMode.COPY;
        if (! tagCompound.contains(NBTKeys.GADGET_MODE, NBT.TAG_BYTE)) {
            setToolMode(stack, mode);
            return mode;
        }
        mode = ToolMode.ofId(tagCompound.getByte(NBTKeys.GADGET_MODE));
        if (mode == null) {
            BuildingGadgets.LOG.debug("Failed to read Tool Mode {} falling back to {}.", tagCompound.getString(NBTKeys.GADGET_MODE), mode);
            mode = ToolMode.COPY;
            setToolMode(stack, mode);
        }
        return mode;
    }

    public static ItemStack getGadget(PlayerEntity player) {
        ItemStack stack = AbstractGadget.getGadget(player);
        if (! (stack.getItem() instanceof GadgetCopyPaste))
            return ItemStack.EMPTY;

        return stack;
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flag) {
        super.addInformation(stack, world, tooltip, flag);
        tooltip.add(TooltipTranslation.GADGET_MODE.componentTranslation(getToolMode(stack)).setStyle(Styles.AQUA));
        addEnergyInformation(tooltip, stack);
        addInformationRayTraceFluid(tooltip, stack);
        EventTooltip.addTemplatePadding(stack, tooltip);
    }

    public void setMode(ItemStack heldItem, int modeInt) {
        // Called when we specify a mode with the radial menu
        ToolMode mode = ToolMode.values()[modeInt];
        setToolMode(heldItem, mode);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getHeldItem(hand);
        player.setActiveHand(hand);
        BlockPos posLookingAt = VectorHelper.getPosLookingAt(player, stack);
        // Remove debug code
        // CapabilityUtil.EnergyUtil.getCap(stack).ifPresent(energy -> energy.receiveEnergy(105000, false));
        if (! world.isRemote()) {
            if (player.isSneaking() && GadgetUtils.setRemoteInventory(stack, player, world, posLookingAt, false) == ActionResultType.SUCCESS)
                return new ActionResult<>(ActionResultType.SUCCESS, stack);

            if (getToolMode(stack) == ToolMode.COPY) {
                if (world.getBlockState(posLookingAt) != Blocks.AIR.getDefaultState())
                    setRegionAndCopy(stack, world, player, posLookingAt);
            } else if (getToolMode(stack) == ToolMode.PASTE && ! player.isSneaking()) {
                BlockPos startPos = getAnchor(stack);
                if (startPos == null && ! world.isAirBlock(posLookingAt))
                    startPos = posLookingAt;
                if (startPos != null)
                    build(stack, world, player, posLookingAt);
            }
        } else {
            if (player.isSneaking()) {
                if (Screen.hasControlDown()) {
                    PacketHandler.sendToServer(new PacketBindTool());
                } else if (GadgetUtils.getRemoteInventory(posLookingAt, world, NetworkIO.Operation.EXTRACT) != null)
                        return new ActionResult<>(ActionResultType.SUCCESS, stack);
            }
            if (getToolMode(stack) == ToolMode.COPY) {
                if (player.isSneaking() && world.getBlockState(posLookingAt) == Blocks.AIR.getDefaultState())
                    GuiMod.COPY.openScreen(player);
            } else if (player.isSneaking()) {
                GuiMod.PASTE.openScreen(player);
            } else {
                BaseRenderer.updateInventoryCache();
            }
        }
        return new ActionResult<>(ActionResultType.SUCCESS, stack);
    }

    private void setRegionAndCopy(ItemStack stack, World world, PlayerEntity player, BlockPos lookedAt) {
        if (player.isSneaking())
            setUpperRegionBound(stack, lookedAt);
        else
            setLowerRegionBound(stack, lookedAt);
        Optional<Region> regionOpt = getSelectedRegion(stack);
        regionOpt.ifPresent(region -> performCopy(stack, world, player, region));
    }

    private void performCopy(ItemStack stack, World world, PlayerEntity player, Region region) {
        LazyOptional<ITemplate> templateCap = stack.getCapability(CapabilityTemplate.TEMPLATE_CAPABILITY, null);
        templateCap.ifPresent(template -> {
            if (! CopyUnloadedCommand.mayCopyUnloadedChunks(player)) {
                ImmutableSortedSet<ChunkPos> unloaded = region.getUnloadedChunks(world);
                if (! unloaded.isEmpty()) {
                    player.sendStatusMessage(MessageTranslation.COPY_UNLOADED.componentTranslation(unloaded.size()).setStyle(Styles.RED), true);
                    BuildingGadgets.LOG.debug("Prevented copy because {} chunks where detected as unloaded.", unloaded.size());
                    BuildingGadgets.LOG.trace("The following chunks were detected as unloaded {}.", CHUNK_JOINER.join(unloaded));
                    return;
                }
            }
            SimpleBuildContext context = SimpleBuildContext.builder()
                    .buildingPlayer(player)
                    .usedStack(stack)
                    .build(world);
            WorldBackedBuildView buildView = WorldBackedBuildView.create(context, region);
            //runCopyTransactionAsync(template, buildView, context);
            runCopyTransactionSync(stack, template, buildView);
        });
    }

    private void runCopyTransactionSync(ItemStack stack, ITemplate template, WorldBackedBuildView buildView) {
        ITemplateTransaction transaction = template.startTransaction();
        if (transaction != null) {
            IBuildContext context = buildView.getContext();
            assert context.getBuildingPlayer() != null;
            CopyScheduler.scheduleCopy(map -> {
                MapBackedBuildView view = MapBackedBuildView.create(context, map);
                ServerTickingScheduler.runTickedStartAndEnd(() -> {
                    try { //TODO check whether this should run async or not
                        transaction
                                .operate(TemplateTransactions.replaceOperator(view))
                                .operate(TemplateTransactions.headerOperator(
                                        "Copy " + getAndIncrementCopyCounter(stack),
                                        context.getBuildingPlayer().getDisplayName().getUnformattedComponentText()))
                                .execute(context);
                    } catch (TransactionExecutionException e) {
                        BuildingGadgets.LOG.error("Transaction Execution failed synchronously this should not have been possible!", e);
                    }
                    return false;
                });
            }, buildView, 4096);
        } else {
            BuildingGadgets.LOG.error("Even though only synchronous operations are performed, No Transaction could be created. This should not be possible");
        }
    }

    private void build(ItemStack stack, World world, PlayerEntity player, BlockPos pos) {
        stack.getCapability(CapabilityTemplate.TEMPLATE_CAPABILITY).ifPresent(template -> {
            SimpleBuildOpenOptions openOptions = SimpleBuildOpenOptions.builder()
                    .context(SimpleBuildContext.builder()
                            .usedStack(stack)
                            .buildingPlayer(player)
                            .build(world))
                    .openType(OpenType.IF_NO_TRANSACTION_OPEN)
                    .build();
            IBuildView view = template.createViewInContext(openOptions);
            if (view != null)
                schedulePlacement(view, pos);
            else
                awaitFreePlacement(template, openOptions, pos);
        });
    }

    private void awaitFreePlacement(ITemplate template, IBuildOpenOptions openOptions, BlockPos pos) {
        ServerTickingScheduler.runTicked(new TimeOutSupplier(TRANSACTION_CREATION_LIMIT) {
            @Override
            protected boolean run() {
                IBuildView view = template.createViewInContext(openOptions);
                if (view != null) {
                    schedulePlacement(view, pos);
                    return true;
                }
                return false;
            }

            @Override
            protected void onTimeout() {
                SimpleBuildOpenOptions forceOptions = SimpleBuildOpenOptions
                        .builderCopyOf(openOptions)
                        .openType(OpenType.DEFAULT)
                        .build();
                schedulePlacement(template.createViewInContext(forceOptions), pos);
            }
        });
    }

    private void schedulePlacement(IBuildView view, BlockPos pos) {
        view.translateTo(pos);
        PlacementScheduler.schedulePlacement(t -> {//TODO PlacementLogic and mechanism to stop when missing blocks!
            EffectBlock.spawnEffectBlock(view.getContext(), t, Mode.PLACE, false);
        }, view, 250);
    }
}