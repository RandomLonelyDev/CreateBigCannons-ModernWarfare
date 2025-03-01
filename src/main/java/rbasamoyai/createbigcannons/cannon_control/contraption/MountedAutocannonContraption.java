package rbasamoyai.createbigcannons.cannon_control.contraption;

import com.simibubi.create.content.contraptions.components.structureMovement.AssemblyException;
import com.simibubi.create.content.contraptions.components.structureMovement.ContraptionType;
import com.simibubi.create.content.contraptions.components.structureMovement.StructureTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.network.PacketDistributor;
import org.jline.utils.Log;
import rbasamoyai.createbigcannons.CBCBlocks;
import rbasamoyai.createbigcannons.CBCContraptionTypes;
import rbasamoyai.createbigcannons.CreateBigCannons;
import rbasamoyai.createbigcannons.cannon_control.ControlPitchContraption;
import rbasamoyai.createbigcannons.cannon_control.effects.CannonPlumeParticleData;
import rbasamoyai.createbigcannons.cannons.ItemCannonBehavior;
import rbasamoyai.createbigcannons.cannons.autocannon.*;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AutocannonCartridgeItem;
import rbasamoyai.createbigcannons.munitions.autocannon.subsonic.SubsonicAutocannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.subsonic.SubsonicAutocannonRoundItem;
import rbasamoyai.createbigcannons.network.CBCNetwork;
import rbasamoyai.createbigcannons.network.ClientboundAnimateCannonContraptionPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MountedAutocannonContraption extends AbstractMountedCannonContraption {
	private AutocannonMaterial cannonMaterial;
	private BlockPos recoilSpringPos;
	private boolean isHandle = false;
	private int silencers = 0;

	@Override
	public float maximumDepression(ControlPitchContraption controller) {
		BlockState state = controller.getControllerState();
		if (CBCBlocks.CANNON_MOUNT.has(state)) return 45;
		if (CBCBlocks.CANNON_CARRIAGE.has(state)) return 15;
		return 0;
	}

	@Override
	public float maximumElevation(ControlPitchContraption controller) {
		BlockState state = controller.getControllerState();
		if (CBCBlocks.CANNON_MOUNT.has(state)) return 90;
		if (CBCBlocks.CANNON_CARRIAGE.has(state)) return this.isHandle ? 45 : 90;
		return 0;
	}

	@Override
	public LazyOptional<IItemHandler> getItemOptional() {
		return this.presentTileEntities.get(this.startPos) instanceof AutocannonBreechBlockEntity breech ? LazyOptional.of(breech::createItemHandler) : LazyOptional.empty();
	}

	@Override
	public boolean assemble(Level level, BlockPos pos) throws AssemblyException {
		if (!this.collectCannonBlocks(level, pos)) return false;
		this.bounds = new AABB(BlockPos.ZERO);
		this.bounds = this.bounds.inflate(Math.ceil(Math.sqrt(getRadius(this.getBlocks().keySet(), Direction.Axis.Y))));
		return !this.blocks.isEmpty();
	}

	private boolean collectCannonBlocks(Level level, BlockPos pos) throws AssemblyException {
		System.out.println("\nRUNNING collectCannonBlocks()\n");
		BlockState startState = level.getBlockState(pos);
		if (!(startState.getBlock() instanceof AutocannonBlock startCannon)) { System.out.println("\n" + startState.getBlock().getName().getString() + "\n"); return false; }
		if (!startCannon.isComplete(startState)) {
			throw hasIncompleteCannonBlocks(pos);
		}

		AutocannonMaterial material = startCannon.getAutocannonMaterial();

		List<StructureBlockInfo> cannonBlocks = new ArrayList<>();
		cannonBlocks.add(new StructureBlockInfo(pos, startState, this.getTileEntityNBT(level, pos)));
		int cannonLength = 1;
		Direction cannonFacing = startCannon.getFacing(startState);
		Direction positive = Direction.get(Direction.AxisDirection.POSITIVE, cannonFacing.getAxis());
		Direction negative = positive.getOpposite();
		BlockPos start = pos;
		BlockState nextState = level.getBlockState(pos.relative(positive));
		boolean positiveBreech = false;

		while (nextState.getBlock() instanceof AutocannonBlock cBlock && this.isConnectedToCannon(level, nextState, start.relative(positive), positive, material)) {
			start = start.relative(positive);
			if(!cBlock.isComplete(nextState)) throw hasIncompleteCannonBlocks(start);
			cannonBlocks.add(new StructureBlockInfo(start, nextState, this.getTileEntityNBT(level, start)));
			cannonLength++;
			positiveBreech = cBlock.isBreechMechanism(nextState);
			nextState = level.getBlockState(start.relative(positive));
			if (cannonLength > getMaxCannonLength()) throw cannonTooLarge();
			if (positiveBreech) break;
		}

		BlockPos positiveEndPos = positiveBreech ? start : start.relative(negative);

		start = pos;
		nextState = level.getBlockState(pos.relative(negative));

		boolean negativeBreech = false;
		while (nextState.getBlock() instanceof AutocannonBlock cBlock && this.isConnectedToCannon(level, nextState, start.relative(negative), negative, material)) { //this loop counts barrels
			start = start.relative(negative);
			if (!cBlock.isComplete(nextState)) throw hasIncompleteCannonBlocks(start);
			cannonBlocks.add(new StructureBlockInfo(start, nextState, this.getTileEntityNBT(level, start)));
			System.out.println("\ncBlockType: " + cBlock.getClass().getSimpleName() + "\n");
			cannonLength++;
			negativeBreech = cBlock.isBreechMechanism(nextState);
			nextState = level.getBlockState(start.relative(negative));
			if (cannonLength > getMaxCannonLength()) throw cannonTooLarge();
			if (negativeBreech) break;
		}

		System.out.println("\nNum of silencers: " + silencers + "\n");

		BlockPos negativeEndPos = negativeBreech ? start : start.relative(positive);

		if (positiveBreech && negativeBreech) throw invalidCannon();

		this.startPos = !positiveBreech && !negativeBreech ? pos : negativeBreech ? negativeEndPos : positiveEndPos;
		BlockState breechState = level.getBlockState(this.startPos);
		if (!(breechState.getBlock() instanceof AutocannonBreechBlock)) throw invalidCannon();
		this.initialOrientation = breechState.getValue(BlockStateProperties.FACING);

		this.anchor = pos;

		this.startPos = this.startPos.subtract(pos);

		for (StructureBlockInfo blockInfo : cannonBlocks) {
			BlockPos localPos = blockInfo.pos.subtract(pos);
			StructureBlockInfo localBlockInfo = new StructureBlockInfo(localPos, blockInfo.state, blockInfo.nbt);
			this.blocks.put(localPos, localBlockInfo);

			if (blockInfo.nbt == null) continue;
			BlockEntity be = BlockEntity.loadStatic(localPos, blockInfo.state, blockInfo.nbt);
			this.presentTileEntities.put(localPos, be);
		}

		StructureBlockInfo startInfo = this.blocks.get(this.startPos);
		if (startInfo == null || !(startInfo.state.getBlock() instanceof AutocannonBreechBlock)) throw noAutocannonBreech();
		this.isHandle = startInfo.state.hasProperty(AutocannonBreechBlock.HANDLE) && startInfo.state.getValue(AutocannonBreechBlock.HANDLE);
		if (this.isHandle) {
			this.getSeats().add(this.startPos.immutable());
		}

		StructureBlockInfo possibleSpring = this.blocks.get(this.startPos.relative(this.initialOrientation));
		if (possibleSpring != null
				&& possibleSpring.state.getBlock() instanceof AutocannonRecoilSpringBlock springBlock
				&& springBlock.getFacing(possibleSpring.state) == this.initialOrientation) {
			this.recoilSpringPos = this.startPos.relative(this.initialOrientation).immutable();
			if (this.presentTileEntities.get(this.recoilSpringPos) instanceof AutocannonRecoilSpringBlockEntity springBE) {
				for (int i = 2; i < cannonLength; ++i) {
					BlockPos pos1 = this.startPos.relative(this.initialOrientation, i);
					StructureBlockInfo blockInfo = this.blocks.get(pos1);
					if (blockInfo == null) continue;
					springBE.toAnimate.put(pos1.subtract(this.recoilSpringPos), blockInfo.state);
					if (blockInfo.state.hasProperty(AutocannonBarrelBlock.ASSEMBLED)) {
						this.blocks.put(pos1, new StructureBlockInfo(pos1, blockInfo.state.setValue(AutocannonBarrelBlock.ASSEMBLED, true), blockInfo.nbt));
					}
				}
				CompoundTag newTag = springBE.saveWithFullMetadata();
				newTag.remove("x");
				newTag.remove("y");
				newTag.remove("z");
				this.blocks.put(this.recoilSpringPos, new StructureBlockInfo(this.recoilSpringPos, possibleSpring.state, newTag));
			}
		}

		this.cannonMaterial = material;

		return true;
	}

	private boolean isConnectedToCannon(LevelAccessor level, BlockState state, BlockPos pos, Direction connection, AutocannonMaterial material) {
		AutocannonBlock cBlock = (AutocannonBlock) state.getBlock();
		if ((cBlock.getAutocannonMaterialInLevel(level, state, pos) != material) && material != AutocannonMaterial.SILENCER) return false;
		return level.getBlockEntity(pos) instanceof IAutocannonBlockEntity cbe
				&& level.getBlockEntity(pos.relative(connection.getOpposite())) instanceof IAutocannonBlockEntity cbe1
				&& cbe.cannonBehavior().isConnectedTo(connection.getOpposite())
				&& cbe1.cannonBehavior().isConnectedTo(connection);
	}

	public static AssemblyException noAutocannonBreech() {
		return new AssemblyException(new TranslatableComponent("exception." + CreateBigCannons.MOD_ID + ".cannon_mount.noAutocannonBreech"));
	}

	@Override
	public void addBlocksToWorld(Level world, StructureTransform transform) {
		Map<BlockPos, StructureBlockInfo> modifiedBlocks = new HashMap<>();
		for (Map.Entry<BlockPos, StructureBlockInfo> entry : this.blocks.entrySet()) {
			StructureBlockInfo info = entry.getValue();
			BlockState newState = info.state;
			boolean modified = true;

			if (newState.hasProperty(AutocannonBarrelBlock.ASSEMBLED) && newState.getValue(AutocannonBarrelBlock.ASSEMBLED)) {
				newState = newState.setValue(AutocannonBarrelBlock.ASSEMBLED, false);
				modified = true;
			}

			if (info.nbt != null) {
				if (info.nbt.contains("AnimateTicks")) {
					info.nbt.remove("AnimateTicks");
					modified = true;
				}
				if (info.nbt.contains("RenderedBlocks")) {
					info.nbt.remove("RenderedBlocks");
					modified = true;
				}
			}

			if (modified) modifiedBlocks.put(info.pos, new StructureBlockInfo(info.pos, newState, info.nbt));
		}
		this.blocks.putAll(modifiedBlocks);
		super.addBlocksToWorld(world, transform);
	}

	@Override
	public void fireShot(ServerLevel level, PitchOrientedContraptionEntity entity) {
		if (this.startPos == null || this.cannonMaterial == null || !(this.presentTileEntities.get(this.startPos) instanceof AutocannonBreechBlockEntity breech) || !breech.canFire()) return;
		ItemStack foundProjectile = breech.extractNextInput();
		if (!(foundProjectile.getItem() instanceof AutocannonCartridgeItem round)) return;
		Vec3 ejectPos = entity.toGlobalVector(Vec3.atCenterOf(this.startPos.relative(this.isHandle ? Direction.DOWN : this.initialOrientation.getOpposite())), 1.0f);
		Vec3 centerPos = entity.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO), 1.0f);
		ItemStack ejectStack = round.getEmptyCartridge(foundProjectile);
		if (!ejectStack.isEmpty()) {
			ItemStack output = breech.insertOutput(ejectStack);
			if (!output.isEmpty()) {
				ItemEntity ejectEntity = new ItemEntity(level, ejectPos.x, ejectPos.y, ejectPos.z, ejectStack);
				Vec3 eject = ejectPos.subtract(centerPos).normalize();
				ejectEntity.setDeltaMovement(eject.scale(this.isHandle ? 0.1 : 0.5));
				level.addFreshEntity(ejectEntity);
			}
		}

		boolean canFail = !CBCConfigs.SERVER.failure.disableAllFailure.get();
		BlockPos currentPos = this.startPos.relative(this.initialOrientation);
		int barrelTravelled = 0;

		while (this.presentTileEntities.get(currentPos) instanceof IAutocannonBlockEntity autocannon) {
			ItemCannonBehavior behavior = autocannon.cannonBehavior();

			if (behavior.canLoadItem(foundProjectile)) {
				++barrelTravelled;
				if (barrelTravelled > this.cannonMaterial.maxLength()) {
					StructureBlockInfo oldInfo = this.blocks.get(currentPos);
					behavior.tryLoadingItem(foundProjectile);
					CompoundTag tag = this.presentTileEntities.get(currentPos).saveWithFullMetadata();
					tag.remove("x");
					tag.remove("y");
					tag.remove("z");
					StructureBlockInfo squibInfo = new StructureBlockInfo(currentPos, oldInfo.state, tag);
					this.blocks.put(currentPos, squibInfo);
					Vec3 squibPos = entity.toGlobalVector(Vec3.atCenterOf(currentPos), 1.0f);
					level.playSound(null, squibPos.x, squibPos.y, squibPos.z, oldInfo.state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 10.0f, 0.0f);
					return;
				}
				currentPos = currentPos.relative(this.initialOrientation);
			} else {
				behavior.removeItem();
				if (canFail) {
					Vec3 failurePoint = entity.toGlobalVector(Vec3.atCenterOf(currentPos), 1.0f);
					level.explode(null, failurePoint.x, failurePoint.y, failurePoint.z, 2, Explosion.BlockInteraction.NONE);
					for (int i = 0; i < 10; ++i) {
						BlockPos pos = currentPos.relative(this.initialOrientation, i);
						this.blocks.remove(pos);
					}
					ControlPitchContraption controller = entity.getController();
					if (controller != null) controller.disassemble();
				}
				return;
			}
		}

		Vec3 spawnPos = entity.toGlobalVector(Vec3.atCenterOf(currentPos.relative(this.initialOrientation)), 1.0f);
		Vec3 vec1 = spawnPos.subtract(centerPos).normalize();
		Vec3 particlePos = spawnPos.subtract(vec1.scale(1.5));

		AbstractAutocannonProjectile projectile = AutocannonCartridgeItem.getAutocannonProjectile(foundProjectile, level);
		if (projectile != null) {
			projectile.setPos(spawnPos);
			projectile.setChargePower(barrelTravelled);
			projectile.setTracer(true);
			projectile.shoot(vec1.x, vec1.y, vec1.z, barrelTravelled, 0.05f);
			projectile.xRotO = projectile.getXRot();
			projectile.yRotO = projectile.getYRot();
			level.addFreshEntity(projectile);
		}

		breech.handleFiring();
		if (this.presentTileEntities.get(this.recoilSpringPos) instanceof AutocannonRecoilSpringBlockEntity spring) spring.handleFiring();
		CBCNetwork.INSTANCE.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), new ClientboundAnimateCannonContraptionPacket(entity));

		for (ServerPlayer player : level.players()) {
			if (entity.getControllingPassenger() == player) continue;
			level.sendParticles(player, new CannonPlumeParticleData(0.1f), true, particlePos.x, particlePos.y, particlePos.z, 0, vec1.x, vec1.y, vec1.z, 1.0f);
		}
		float pitch = silencers > 0 ? 4F : 2F;
		float volume = 4F - silencers - (projectile instanceof SubsonicAutocannonProjectile ? 3.2F : 0);
		volume = volume <= 0 ? 0.2F : volume;
		level.playSound(null, spawnPos.x, spawnPos.y, spawnPos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, volume, pitch);
	}

	@Override
	public void animate() {
		super.animate();
		if (this.presentTileEntities.get(this.startPos) instanceof AutocannonBreechBlockEntity breech) breech.handleFiring();
		if (this.presentTileEntities.get(this.recoilSpringPos) instanceof AutocannonRecoilSpringBlockEntity spring) spring.handleFiring();
	}

	@Override
	public void tick(Level level, PitchOrientedContraptionEntity entity) {
		super.tick(level, entity);

		if (level instanceof ServerLevel slevel && this.canBeFiredOnController(entity.getController())) this.fireShot(slevel, entity);

		for (Map.Entry<BlockPos, BlockEntity> entry : this.presentTileEntities.entrySet()) {
			if (entry.getValue() instanceof IAutocannonBlockEntity autocannon) autocannon.tickFromContraption(level, entity, entry.getKey());
		}
	}

	@Override
	public BlockPos getSeatPos(Entity entity) {
		return entity == this.entity.getControllingPassenger() ? this.startPos.relative(this.initialOrientation.getOpposite()) : super.getSeatPos(entity);
	}

	@Override public boolean canBeTurnedByController(ControlPitchContraption control) { return !this.isHandle; }

	@Override public boolean canBeTurnedByPassenger(Entity entity) {
		return this.isHandle && entity instanceof Player;
	}
	@Override public boolean canBeFiredOnController(ControlPitchContraption control) { return !this.isHandle && this.entity.getVehicle() != control; }

	@Override
	public void onRedstoneUpdate(ServerLevel level, PitchOrientedContraptionEntity entity, boolean togglePower, int firePower) {
		if (this.presentTileEntities.get(this.startPos) instanceof AutocannonBreechBlockEntity breech) breech.setFireRate(firePower);
	}

	public void trySettingFireRateCarriage(int fireRateAdjustment) {
		if (this.presentTileEntities.get(this.startPos) instanceof AutocannonBreechBlockEntity breech
			&& (fireRateAdjustment > 0 || breech.getFireRate() > 1)) // Can't turn off carriage autocannon
			breech.setFireRate(breech.getFireRate() + fireRateAdjustment);
	}

	public int getReferencedFireRate() {
		return this.presentTileEntities.get(this.startPos) instanceof AutocannonBreechBlockEntity breech ? breech.getActualFireRate() : 0;
	}

	@Override public float getWeightForStress() { return this.cannonMaterial == null ? this.blocks.size() : this.blocks.size() * this.cannonMaterial.weight(); }

	@Override
	public CompoundTag writeNBT(boolean clientData) {
		CompoundTag tag = super.writeNBT(clientData);
		tag.putString("AutocannonMaterial", this.cannonMaterial == null ? AutocannonMaterial.CAST_IRON.name().toString() : this.cannonMaterial.name().toString());
		if (this.startPos != null) tag.put("StartPos", NbtUtils.writeBlockPos(this.startPos));
		if (this.recoilSpringPos != null) tag.put("RecoilSpringPos", NbtUtils.writeBlockPos(this.recoilSpringPos));
		tag.putBoolean("IsHandle", this.isHandle);
		return tag;
	}

	@Override
	public void readNBT(Level level, CompoundTag tag, boolean clientData) {
		super.readNBT(level, tag, clientData);
		this.cannonMaterial = AutocannonMaterial.fromName(new ResourceLocation(tag.getString("AutocannonMaterial")));
		this.startPos = tag.contains("StartPos") ? NbtUtils.readBlockPos(tag.getCompound("StartPos")) : null;
		this.recoilSpringPos = tag.contains("RecoilSpringPos") ? NbtUtils.readBlockPos(tag.getCompound("RecoilSpringPos")) : null;
		this.isHandle = tag.getBoolean("IsHandle");
	}

	@Override protected ContraptionType getType() { return CBCContraptionTypes.MOUNTED_AUTOCANNON; }

}
