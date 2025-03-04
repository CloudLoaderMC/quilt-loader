/*
 * Copyright 2022 QuiltMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.quiltmc.loader.impl.plugin.quilt;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.quiltmc.json5.exception.ParseException;
import org.quiltmc.loader.api.LoaderValue;
import org.quiltmc.loader.api.ModDependency;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.loader.api.Version;
import org.quiltmc.loader.api.plugin.ModLocation;
import org.quiltmc.loader.api.plugin.ModMetadataExt;
import org.quiltmc.loader.api.plugin.ModMetadataExt.ProvidedMod;
import org.quiltmc.loader.api.plugin.QuiltPluginContext;
import org.quiltmc.loader.api.plugin.QuiltPluginError;
import org.quiltmc.loader.api.plugin.QuiltPluginManager;
import org.quiltmc.loader.api.plugin.gui.PluginGuiIcon;
import org.quiltmc.loader.api.plugin.gui.PluginGuiManager;
import org.quiltmc.loader.api.plugin.gui.PluginGuiTreeNode;
import org.quiltmc.loader.api.plugin.gui.PluginGuiTreeNode.SortOrder;
import org.quiltmc.loader.api.plugin.gui.QuiltLoaderText;
import org.quiltmc.loader.api.plugin.solver.AliasedLoadOption;
import org.quiltmc.loader.api.plugin.solver.LoadOption;
import org.quiltmc.loader.api.plugin.solver.ModLoadOption;
import org.quiltmc.loader.api.plugin.solver.RuleContext;
import org.quiltmc.loader.impl.filesystem.QuiltJoinedFileSystem;
import org.quiltmc.loader.impl.game.GameProvider;
import org.quiltmc.loader.impl.game.GameProvider.BuiltinMod;
import org.quiltmc.loader.impl.metadata.qmj.InternalModMetadata;
import org.quiltmc.loader.impl.metadata.qmj.ModMetadataReader;
import org.quiltmc.loader.impl.metadata.qmj.QuiltOverrides;
import org.quiltmc.loader.impl.metadata.qmj.QuiltOverrides.ModOverrides;
import org.quiltmc.loader.impl.metadata.qmj.V1ModMetadataBuilder;
import org.quiltmc.loader.impl.plugin.BuiltinQuiltPlugin;
import org.quiltmc.loader.impl.util.SystemProperties;
import org.quiltmc.loader.impl.util.log.Log;
import org.quiltmc.loader.impl.util.log.LogCategory;

/** Quilt-loader's plugin. For simplicities sake this is a builtin plugin - and cannot be disabled, or reloaded (since
 * quilt-loader can't reload itself to a different version). */
public class StandardQuiltPlugin extends BuiltinQuiltPlugin {

	public static final boolean DEBUG_PRINT_STATE = Boolean.getBoolean(SystemProperties.DEBUG_MOD_SOLVING);

	private QuiltOverrides overrides;
	private final Map<String, OptionalModIdDefintion> modDefinitions = new HashMap<>();

	@Override
	public void load(QuiltPluginContext context, Map<String, LoaderValue> previousData) {
		super.load(context, previousData);
		loadOverrides();
	}

	private void loadOverrides() {
		try {
			Path overrideFile = context().manager().getConfigDirectory().resolve("quilt-loader-overrides.json");
			overrides = new QuiltOverrides(overrideFile);
		} catch (ParseException | IOException e) {
			QuiltPluginError error = context().reportError(
				QuiltLoaderText.translate("error.quilt_overrides.io_parse.title")
			);
			error.appendDescription(QuiltLoaderText.of(e.getMessage()));
			error.appendThrowable(e);
		}
	}

	public void addBuiltinMods(GameProvider game) {
		int gameIndex = 1;
		for (BuiltinMod mod : game.getBuiltinMods()) {
			addBuiltinMod(mod, "game-" + gameIndex);
			gameIndex++;
		}

		String javaVersion = System.getProperty("java.specification.version").replaceFirst("^1\\.", "");
		V1ModMetadataBuilder javaMeta = new V1ModMetadataBuilder();
		javaMeta.id = "java";
		javaMeta.group = "builtin";
		javaMeta.version = Version.of(javaVersion);
		javaMeta.name = System.getProperty("java.vm.name");
		Path javaPath = new File(System.getProperty("java.home")).toPath();
		addSystemMod(new BuiltinMod(Collections.singletonList(javaPath), javaMeta.build()), "java");
	}

	private void addSystemMod(BuiltinMod mod, String name) {
		addInternalMod(mod, name, true);
	}

	private void addBuiltinMod(BuiltinMod mod, String name) {
		addInternalMod(mod, name, false);
	}

	private void addInternalMod(BuiltinMod mod, String name, boolean system) {

		boolean changed = false;
		List<Path> openedPaths = new ArrayList<>();

		for (Path from : mod.paths) {

			Path inside = null;

			if (from.getFileName().toString().endsWith(".jar")) {
				try {
					inside = FileSystems.newFileSystem(from, (ClassLoader) null).getPath("/");
				} catch (IOException e) {
					// A bit odd, but not necessarily a crash-worthy issue
					e.printStackTrace();
				}
			}

			if (inside == null) {
				openedPaths.add(from);
			} else {
				changed = true;
				openedPaths.add(inside);
			}
		}

		Path from = join(mod.paths, name);
		Path inside = changed ? join(openedPaths, name) : from;

		// We don't go via context().addModOption since we don't really have a good gui node to base it off
		context().ruleContext().addOption(
			system //
				? new SystemModOption(context(), mod.metadata, from, inside) //
				: new BuiltinModOption(context(), mod.metadata, from, inside)
		);
	}

	private static Path join(List<Path> paths, String name) {
		if (paths.size() == 1) {
			return paths.get(0);
		} else {
			return new QuiltJoinedFileSystem(name, paths).getRoot();
		}
	}

	@Override
	public ModLoadOption[] scanZip(Path root, ModLocation location, PluginGuiTreeNode guiNode) throws IOException {

		Path parent = context().manager().getParent(root);

		if (!parent.getFileName().toString().endsWith(".jar")) {
			return null;
		}

		return scan0(root, guiNode.manager().iconJarFile(), location, true, guiNode);
	}

	@Override
	public ModLoadOption[] scanFolder(Path folder, ModLocation location, PluginGuiTreeNode guiNode) throws IOException {
		return scan0(folder, guiNode.manager().iconFolder(), location, false, guiNode);
	}

	private ModLoadOption[] scan0(Path root, PluginGuiIcon fileIcon, ModLocation location, boolean isZip,
		PluginGuiTreeNode guiNode) throws IOException {

		Path qmj = root.resolve("quilt.mod.json");
		if (!Files.isRegularFile(qmj)) {
			return null;
		}

		try {
			InternalModMetadata meta = ModMetadataReader.read(qmj);

			Path from = root;
			if (isZip) {
				from = context().manager().getParent(root);
			}

			jars: for (String jar : meta.jars()) {
				Path inner = root;
				for (String part : jar.split("/")) {
					if ("..".equals(part)) {
						continue jars;
					}
					inner = inner.resolve(part);
				}

				if (inner == from) {
					continue;
				}

				PluginGuiTreeNode jarNode = guiNode.addChild(QuiltLoaderText.of(jar), SortOrder.ALPHABETICAL_ORDER);
				context().addFileToScan(inner, jarNode);
			}

			// a mod needs to be remapped if we are in a development environment, and the mod
			// did not come from the classpath
			boolean requiresRemap = !location.onClasspath() && QuiltLoader.isDevelopmentEnvironment();
			return new ModLoadOption[] { new QuiltModOption(
				context(), meta, from, fileIcon, root, location.isDirect(), requiresRemap
			) };
		} catch (ParseException parse) {
			QuiltLoaderText title = QuiltLoaderText.translate(
				"gui.text.invalid_metadata.title", "quilt.mod.json", parse.getMessage()
			);
			QuiltPluginError error = context().reportError(title);
			String describedPath = context().manager().describePath(qmj);
			error.appendReportText("Invalid 'quilt.mod.json' metadata file:" + describedPath);
			error.appendDescription(QuiltLoaderText.translate("gui.text.invalid_metadata.desc.0", describedPath));
			error.appendThrowable(parse);
			PluginGuiManager guiManager = context().manager().getGuiManager();
			error.addFileViewButton(
				QuiltLoaderText.translate("button.view_file"), //
				context().manager().getRealContainingFile(root)
			).icon(guiManager.iconJarFile().withDecoration(guiManager.iconQuilt()));

			guiNode.addChild(QuiltLoaderText.translate("gui.text.invalid_metadata", parse.getMessage()))//
				.setError(parse, error);
			return null;
		}
	}

	@Override
	public void onLoadOptionAdded(LoadOption option) {

		// We handle dependency solving for all plugins that don't tell us not to.

		if (option instanceof AliasedLoadOption) {
			AliasedLoadOption alias = (AliasedLoadOption) option;
			if (alias.getTarget() != null) {
				return;
			}
		}

		if (option instanceof ModLoadOption) {
			ModLoadOption mod = (ModLoadOption) option;
			ModMetadataExt metadata = mod.metadata();
			RuleContext ctx = context().ruleContext();

			OptionalModIdDefintion def = modDefinitions.get(mod.id());
			if (def == null) {
				def = new OptionalModIdDefintion(ctx, mod.id());
				modDefinitions.put(mod.id(), def);
				ctx.addRule(def);
			}

			// TODO: this minecraft-specific extension should be moved to its own plugin
			// If the mod's environment doesn't match the current one,
			// then add a rule so that the mod is never loaded.
			if (!metadata.environment().matches(context().manager().getEnvironment())) {
				ctx.addRule(new DisabledModIdDefinition(mod));
				return;
			}

			if (mod.isMandatory()) {
				ctx.addRule(new MandatoryModIdDefinition(mod));
			}

			if (metadata.shouldQuiltDefineProvides()) {
				Collection<? extends ProvidedMod> provides = metadata.provides();

				for (ProvidedMod provided : provides) {
					PluginGuiTreeNode guiNode = context().manager().getGuiNode(mod)//
						.addChild(QuiltLoaderText.translate("gui.text.providing", provided.id()));
					guiNode.mainIcon(guiNode.manager().iconUnknownFile());
					context().addModLoadOption(new ProvidedModOption(mod, provided), guiNode);
				}
			}

			if (metadata.shouldQuiltDefineDependencies()) {

				Path path = mod.from();
				String described = context().manager().describePath(path);
				if (Boolean.getBoolean(SystemProperties.DEBUG_DUMP_OVERRIDE_PATHS)) {
					Log.info(LogCategory.DISCOVERY, "Override path: '" + described + "'");
				}

				Collection<ModDependency> depends = metadata.depends();
				Collection<ModDependency> breaks = metadata.breaks();

				ModOverrides override = overrides.overrides.get(described);

				if (override != null) {
					depends = new HashSet<>(depends);
					breaks = new HashSet<>(breaks);

					for (Map.Entry<ModDependency, ModDependency> entry : override.dependsOverrides.entrySet()) {
						if (!depends.remove(entry.getKey())) {
							Log.warn(
								LogCategory.DISCOVERY, "Failed to find the dependency " + entry.getKey()
									+ " to override in " + metadata.depends()
							);
							continue;
						}
						depends.add(entry.getValue());
					}

					for (Map.Entry<ModDependency, ModDependency> entry : override.breakOverrides.entrySet()) {
						if (!breaks.remove(entry.getKey())) {
							Log.warn(
								LogCategory.DISCOVERY, "Failed to find the breaks " + entry.getKey()
									+ " to override in " + metadata.breaks()
							);
							continue;
						}
						breaks.add(entry.getValue());
					}
				}

				for (ModDependency dep : depends) {
					if (!dep.shouldIgnore()) {
						ctx.addRule(createModDepLink(context().manager(), ctx, mod, dep));
					}
				}

				for (ModDependency dep : breaks) {
					if (!dep.shouldIgnore()) {
						ctx.addRule(createModBreaks(context().manager(), ctx, mod, dep));
					}
				}
			}
		}
	}

	public static QuiltRuleDep createModDepLink(QuiltPluginManager manager, RuleContext ctx, LoadOption option,
		ModDependency dep) {

		if (dep instanceof ModDependency.Any) {
			ModDependency.Any any = (ModDependency.Any) dep;

			return new QuiltRuleDepAny(manager, ctx, option, any);
		} else {
			ModDependency.Only only = (ModDependency.Only) dep;

			return new QuiltRuleDepOnly(manager, ctx, option, only);
		}
	}

	public static QuiltRuleBreak createModBreaks(QuiltPluginManager manager, RuleContext ctx, LoadOption option,
		ModDependency dep) {
		if (dep instanceof ModDependency.All) {
			ModDependency.All any = (ModDependency.All) dep;

			return new QuiltRuleBreakAll(manager, ctx, option, any);
		} else {
			ModDependency.Only only = (ModDependency.Only) dep;

			return new QuiltRuleBreakOnly(manager, ctx, option, only);
		}
	}
}
