package rbasamoyai.createbigcannons.cannons.big_cannons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import rbasamoyai.createbigcannons.CBCBlockEntities;
import rbasamoyai.createbigcannons.CBCShapes;
import rbasamoyai.createbigcannons.crafting.casting.CannonCastShape;

public class BigCannonEndBlock extends SolidBigCannonBlock<BigCannonEndBlockEntity> {
	
	public BigCannonEndBlock(Properties properties, BigCannonMaterial cannonMaterial) {
		super(properties, cannonMaterial);
	}
	
	@Override public boolean isDoubleSidedCannon(BlockState state) { return false; }
	
	@Override public Direction getFacing(BlockState state) { return super.getFacing(state).getOpposite(); }
	
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
	}
	
	@Override
	public VoxelShape getShape(BlockState state, BlockGetter blockGetter, BlockPos pos, CollisionContext context) {
		return CBCShapes.CANNON_END.get(state.getValue(FACING));
	}
	
	@Override public boolean isComplete(BlockState state) { return true; }
	
	@Override public CannonCastShape getCannonShape() { return CannonCastShape.CANNON_END.get(); }

	@Override public Class<BigCannonEndBlockEntity> getTileEntityClass() { return BigCannonEndBlockEntity.class; }
	@Override public BlockEntityType<? extends BigCannonEndBlockEntity> getTileEntityType() { return CBCBlockEntities.CANNON_END.get(); }
	
}
