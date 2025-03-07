package rbasamoyai.createbigcannons.munitions.big_cannon.shrapnel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.CreateBigCannons;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.munitions.config.BlockHardnessHandler;

public class Shrapnel extends AbstractHurtingProjectile {
	
	private int age;
	protected float damage;
	
	public Shrapnel(EntityType<? extends Shrapnel> type, Level level) {
		super(type, level);
	}
	
	@Override
	public void tick() {
		super.tick();
		++this.age;
		
		if (!this.isNoGravity() && this.getGravity() < 0) {
			this.setDeltaMovement(this.getDeltaMovement().add(0.0f, this.getGravity(), 0.0f));
		}
		
		if (!this.level.isClientSide && this.age > 20) {
			this.discard();
		}
	}
	
	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putInt("Age", this.age);
		tag.putFloat("Damage", this.damage);
	}
	
	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		this.age = tag.getInt("Age");
		this.damage = tag.getFloat("Damage");
	}
	
	public void setDamage(float damage) { this.damage = damage; }
	public float getDamage() { return this.damage; }
	
	@Override
	protected void onHitBlock(BlockHitResult result) {
		super.onHitBlock(result);
		BlockPos pos = result.getBlockPos();
		BlockState state = this.level.getChunk(pos).getBlockState(pos);
		if (!this.level.isClientSide && state.getDestroySpeed(this.level, pos) != -1 && this.canDestroyBlock(state)) {
			Vec3 curVel = this.getDeltaMovement();
			double curPom = this.getProjectileMass() * curVel.length();
			double hardness = BlockHardnessHandler.getHardness(state) * 10;
			CreateBigCannons.BLOCK_DAMAGE.damageBlock(pos.immutable(), (int) Math.min(curPom, hardness), state, this.level);

			SoundType type = state.getSoundType();
			this.level.playSound(null, pos, type.getBreakSound(), SoundSource.NEUTRAL, type.getVolume() * 0.25f, type.getPitch());
			this.discard();
		}
	}
	
	protected boolean canDestroyBlock(BlockState state) { return true; }

	protected float getProjectileMass() { return 1; }
	
	@Override
	protected void onHitEntity(EntityHitResult result) {
		result.getEntity().hurt(this.getDamageSource(), this.damage);
		if (!CBCConfigs.SERVER.munitions.invulProjectileHurt.get()) result.getEntity().invulnerableTime = 0;
	}
	
	@Override
	protected void onHit(HitResult result) {
		super.onHit(result);
		if (!this.level.isClientSide && (!(result instanceof EntityHitResult eResult) || eResult.getEntity().getType() != this.getType())) this.discard();
	}

	public static final DamageSource SHRAPNEL = new DamageSource(CreateBigCannons.MOD_ID + ".shrapnel");
	protected DamageSource getDamageSource() { return SHRAPNEL; }
	
	public static void build(EntityType.Builder<? extends Shrapnel> builder) {
		builder.setTrackingRange(3)
				.setUpdateInterval(20)
				.setShouldReceiveVelocityUpdates(true)
				.fireImmune()
				.sized(0.25f, 0.25f);
	}
	
	@Override protected float getEyeHeight(Pose pose, EntityDimensions dimensions) { return 0.125f; }
	
	@Override protected float getInertia() { return 0.99f; }
	
	protected double getGravity() { return 0; }

	@Override protected boolean canHitEntity(Entity entity) { return super.canHitEntity(entity) && !(entity instanceof Projectile); }

	public static <T extends Shrapnel> List<T> spawnShrapnelBurst(Level level, EntityType<T> type, Vec3 position, Vec3 initialVelocity, int count, double spread, float damage) {
		Vec3 forward = initialVelocity.normalize();
		Vec3 right = forward.cross(new Vec3(Direction.UP.step()));
		Vec3 up = forward.cross(right);
		double length = initialVelocity.length();
		Random random = level.getRandom();
		List<T> list = new ArrayList<>();
		
		for (int i = 0; i < count; ++i) {
			double velScale = length * (1.4d + 0.2d * random.nextDouble());
			Vec3 vel = forward.scale(velScale)
					.add(right.scale((random.nextDouble() - random.nextDouble()) * velScale * spread))
					.add(up.scale((random.nextDouble() - random.nextDouble()) * velScale * spread));
			
			T shrapnel = type.create(level);
			double rx = position.x + (random.nextDouble() - random.nextDouble()) * 0.0625d;
			double ry = position.y + (random.nextDouble() - random.nextDouble()) * 0.0625d;
			double rz = position.z + (random.nextDouble() - random.nextDouble()) * 0.0625d;
			shrapnel.setPos(rx, ry, rz);
			shrapnel.setDeltaMovement(vel);
			shrapnel.setDamage(damage);
			
			if (level.addFreshEntity(shrapnel)) list.add(shrapnel);
		}
		
		if (list.size() != count) {
			CreateBigCannons.LOGGER.info("Shrapnel burst failed to spawn {} out of {} shrapnel bullets", count - list.size(), count);
		}
		return list;
	}

}
