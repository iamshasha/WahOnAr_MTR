package mtr.mappings;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Block properties, said the same way on every Minecraft version this mod builds for.
 *
 * 1.20 deleted {@code Material} outright and renamed {@code MaterialColor} to {@code MapColor}. Both were named
 * directly in about sixty places, so without this the same block definitions could not be written once and built
 * for both. The colour is passed as {@link BlockColor}, which names no Minecraft type at all, so the call sites
 * do not have to know which version they are being compiled for.
 *
 * Generated: the build copies the variant matching the Minecraft version over this file. Edit the variants in
 * common/src/main/blockproperties, not this.
 */
public interface BlockProperties {

	static BlockBehaviour.Properties metal(BlockColor blockColor) {
		return BlockBehaviour.Properties.of().mapColor(color(blockColor));
	}

	static BlockBehaviour.Properties metal() {
		return BlockBehaviour.Properties.of();
	}

	static BlockBehaviour.Properties stone(BlockColor blockColor) {
		return BlockBehaviour.Properties.of().mapColor(color(blockColor));
	}

	static BlockBehaviour.Properties stone() {
		return BlockBehaviour.Properties.of();
	}

	static BlockBehaviour.Properties ice(BlockColor blockColor) {
		return BlockBehaviour.Properties.of().mapColor(color(blockColor));
	}

	static BlockBehaviour.Properties ice() {
		return BlockBehaviour.Properties.of();
	}


	static MapColor color(BlockColor blockColor) {
		switch (blockColor) {
			case COLOR_BLACK:
				return MapColor.COLOR_BLACK;
			case COLOR_BLUE:
				return MapColor.COLOR_BLUE;
			case COLOR_BROWN:
				return MapColor.COLOR_BROWN;
			case COLOR_GRAY:
				return MapColor.COLOR_GRAY;
			case COLOR_LIGHT_GRAY:
				return MapColor.COLOR_LIGHT_GRAY;
			case COLOR_MAGENTA:
				return MapColor.COLOR_MAGENTA;
			case COLOR_ORANGE:
				return MapColor.COLOR_ORANGE;
			case COLOR_PURPLE:
				return MapColor.COLOR_PURPLE;
			case COLOR_RED:
				return MapColor.COLOR_RED;
			case COLOR_YELLOW:
				return MapColor.COLOR_YELLOW;
			case DIAMOND:
				return MapColor.DIAMOND;
			case EMERALD:
				return MapColor.EMERALD;
			case ICE:
				return MapColor.ICE;
			case METAL:
				return MapColor.METAL;
			case NETHER:
				return MapColor.NETHER;
			case QUARTZ:
				return MapColor.QUARTZ;
			case SNOW:
				return MapColor.SNOW;
			case STONE:
				return MapColor.STONE;
			case TERRACOTTA_CYAN:
				return MapColor.TERRACOTTA_CYAN;
			case TERRACOTTA_LIGHT_BLUE:
				return MapColor.TERRACOTTA_LIGHT_BLUE;
			case TERRACOTTA_RED:
				return MapColor.TERRACOTTA_RED;
			case TERRACOTTA_YELLOW:
				return MapColor.TERRACOTTA_YELLOW;
			case WOOD:
				return MapColor.WOOD;
		}
		return MapColor.NONE;
	}

	/** 1.19 asks the material; 1.20 deleted it and moved the question to the state. */
	static boolean isSolid(net.minecraft.world.level.block.state.BlockState blockState) {
		return blockState.isSolid();
	}

	/** The packed colour of a dye, which 1.20 renamed along with the class it returns. */
	static int colorOf(net.minecraft.world.item.DyeColor dyeColor) {
		return dyeColor.getMapColor().col;
	}

	/** The packed colour a block shows on a map by default, renamed the same way. */
	static int defaultColorOf(net.minecraft.world.level.block.Block block) {
		return block.defaultMapColor().col;
	}
}
