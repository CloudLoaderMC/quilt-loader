package org.quiltmc.loader.api.plugin.solver;

import java.io.IOException;
import java.nio.file.Path;

import org.jetbrains.annotations.Nullable;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.Version;
import org.quiltmc.loader.api.plugin.ModContainerExt;
import org.quiltmc.loader.api.plugin.ModMetadataExt;
import org.quiltmc.loader.api.plugin.ModMetadataExt.ModLoadType;
import org.quiltmc.loader.api.plugin.QuiltLoaderPlugin;
import org.quiltmc.loader.api.plugin.QuiltPluginContext;
import org.quiltmc.loader.api.plugin.gui.PluginGuiIcon;
import org.quiltmc.loader.api.plugin.gui.PluginGuiTreeNode;

/** A special type of {@link LoadOption} that represents a mod. */
public abstract class ModLoadOption extends LoadOption {

	/** @return The plugin context for the plugin that loaded this mod. */
	public abstract QuiltPluginContext loader();

	/** @return The metadata that this mod either (a) is, or (b) points to, if this is an {@link AliasedLoadOption}. */
	public abstract ModMetadataExt metadata();

	/** @return The {@link Path} where this is loaded from. This should be either the Path that was passed to
	 *         {@link QuiltLoaderPlugin#scanZip(Path, PluginGuiTreeNode)} or the Path that was passed to
	 *         {@link QuiltLoaderPlugin#scanUnknownFile(Path, PluginGuiTreeNode)}. */
	public abstract Path from();

	/** @return The {@link Path} where this mod's classes and resources can be loaded from. */
	public abstract Path resourceRoot();

	/** @return True if this mod MUST be loaded or false if this should be loaded depending on it's {@link ModLoadType}.
	 *         Quilt returns true here for mods on the classpath and directly in the mods folder, but not when
	 *         jar-in-jar'd. */
	public abstract boolean isMandatory();

	// TODO: How do we turn this into a ModContainer?
	// like... how should we handle mods that need remapping vs those that don't?
	// plus how is that meant to work with caches in the future?

	/** @return The namespace to map classes from, or null if this mod shouldn't have it's classes remapped. */
	@Nullable
	public abstract String namespaceMappingFrom();

	public abstract boolean needsChasmTransforming();

	/** @return A hash of the origin files used for the mod. This is used to cache class transformations (like remapping
	 *         and chasm) between launches. This may be called off-thread. */
	public abstract byte[] computeOriginHash() throws IOException;

	/** @return The group for this mod. Normally this will just be the same as the group in {@link #metadata()}, but for
	 *         provided mods this may be different. */
	public String group() {
		return metadata().group();
	}

	/** @return The id for this mod. Normally this will just be the same as the id in {@link #metadata()}, but for
	 *         provided mods this may be different. */
	public String id() {
		return metadata().id();
	}

	/** @return The version for this mod. Normally this will just be the same as the version in {@link #metadata()}, but
	 *         for provided mods this may be different. */
	public Version version() {
		return metadata().version();
	}

	public abstract PluginGuiIcon modTypeIcon();

	public abstract ModContainerExt convertToMod(Path transformedResourceRoot);

	@Override
	public String toString() {
		return shortString();
	}

	/** Older temporary method for error descriptions */
	@Deprecated
	public abstract String shortString();

	/** Older temporary method for error descriptions */
	@Deprecated
	public String fullString() {
		return shortString() + " " + getSpecificInfo();
	}

	/** Older temporary method for error descriptions */
	@Deprecated
	public String getLoadSource() {
		return loader().manager().describePath(from());
	}

	/** Older temporary method for error descriptions */
	@Deprecated
	public abstract String getSpecificInfo();
}
