package mtr.mappings;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MaterialColor;
import net.minecraft.world.level.material.Material;

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
		return BlockBehaviour.Properties.of(Material.METAL, color(blockColor));
	}

	static BlockBehaviour.Properties metal() {
		return BlockBehaviour.Properties.of(Material.METAL);
	}

	static BlockBehaviour.Properties stone(BlockColor blockColor) {
		return BlockBehaviour.Properties.of(Material.STONE, color(blockColor));
	}

	static BlockBehaviour.Properties stone() {
		return BlockBehaviour.Properties.of(Material.STONE);
	}

	static BlockBehaviour.Properties ice(BlockColor blockColor) {
		return BlockBehaviour.Properties.of(Material.ICE, color(blockColor));
	}

	static BlockBehaviour.Properties ice() {
		return BlockBehaviour.Properties.of(Material.ICE);
	}


	static MaterialColor color(BlockColor blockColor) {
		switch (blockColor) {
			case COLOR_BLACK:
				return MaterialColor.COLOR_BLACK;
			case COLOR_BLUE:
				return MaterialColor.COLOR_BLUE;
			case COLOR_BROWN:
				return MaterialColor.COLOR_BROWN;
			case COLOR_GRAY:
				return MaterialColor.COLOR_GRAY;
			case COLOR_LIGHT_GRAY:
				return MaterialColor.COLOR_LIGHT_GRAY;
			case COLOR_MAGENTA:
				return MaterialColor.COLOR_MAGENTA;
			case COLOR_ORANGE:
				return MaterialColor.COLOR_ORANGE;
			case COLOR_PURPLE:
				return MaterialColor.COLOR_PURPLE;
			case COLOR_RED:
				return MaterialColor.COLOR_RED;
			case COLOR_YELLOW:
				return MaterialColor.COLOR_YELLOW;
			case DIAMOND:
				return MaterialColor.DIAMOND;
			case EMERALD:
				return MaterialColor.EMERALD;
			case ICE:
				return MaterialColor.ICE;
			case METAL:
				return MaterialColor.METAL;
			case NETHER:
				return MaterialColor.NETHER;
			case QUARTZ:
				return MaterialColor.QUARTZ;
			case SNOW:
				return MaterialColor.SNOW;
			case STONE:
				return MaterialColor.STONE;
			case TERRACOTTA_CYAN:
				return MaterialColor.TERRACOTTA_CYAN;
			case TERRACOTTA_LIGHT_BLUE:
				return MaterialColor.TERRACOTTA_LIGHT_BLUE;
			case TERRACOTTA_RED:
				return MaterialColor.TERRACOTTA_RED;
			case TERRACOTTA_YELLOW:
				return MaterialColor.TERRACOTTA_YELLOW;
			case WOOD:
				return MaterialColor.WOOD;
		}
		return MaterialColor.NONE;
	}

	/** 1.19 asks the material; 1.20 deleted it and moved the question to the state. */
	static boolean isSolid(net.minecraft.world.level.block.state.BlockState blockState) {
		return blockState.getMaterial().isSolid();
	}

	/** The packed colour of a dye, which 1.20 renamed along with the class it returns. */
	static int colorOf(net.minecraft.world.item.DyeColor dyeColor) {
		return dyeColor.getMaterialColor().col;
	}

	/** The packed colour a block shows on a map by default, renamed the same way. */
	static int defaultColorOf(net.minecraft.world.level.block.Block block) {
		return block.defaultMaterialColor().col;
	}
}
