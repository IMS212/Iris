package net.irisshaders.iris.pipeline.transform.vulkan;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.FunctionDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.ConditionExpression;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.ArrayAccessExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.GroupingExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.PreciseQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.PrecisionQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.FunctionPrototype;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import io.github.douira.glsl_transformer.util.LRUCache;
import io.github.douira.glsl_transformer.util.Type;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.irisshaders.iris.pipeline.transform.Glsl;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.pipeline.transform.VulkanPushConstants;
import net.irisshaders.iris.pipeline.transform.UniformPlan;
import net.irisshaders.iris.pipeline.transform.transformer.CompatibilityTransformer;
import net.irisshaders.iris.pipeline.transform.transformer.LayoutTransformer;
import net.irisshaders.iris.samplers.SamplerBindingPlan;
import net.irisshaders.iris.samplers.SamplerHeapLayout;
import net.irisshaders.iris.samplers.VulkanHeapLayout;
import net.irisshaders.iris.targets.ColorAttachmentPlan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class VulkanShaderPatcher {
	private static final Map<Map<PatchShaderType, String>, PatchedShader> discoveryCache = new LRUCache<>(256);
	private static final SingleASTTransformer<JobParameters> parser = new SingleASTTransformer<>();

	static {
		parser.setParsingCacheStrategy(ASTParser.ParsingCacheStrategy.TWO_TIER);
	}

	public static PatchedShader patch(Map<PatchShaderType, String> sources) {
		return patch(sources, null);
	}

	public static PatchedShader patch(Map<PatchShaderType, String> sources, SamplerBindingPlan samplers) {
		return patch(sources, samplers, null);
	}

	public static PatchedShader patch(Map<PatchShaderType, String> sources, SamplerBindingPlan samplers,
		ColorAttachmentPlan.Program outputs) {
		return patch(sources, samplers, outputs, name -> false);
	}

	public static PatchedShader discover(Map<PatchShaderType, String> sources) {
		var key = new EnumMap<PatchShaderType, String>(PatchShaderType.class);
		sources.forEach((stage, source) -> {
			if (source != null) {
				key.put(stage, source);
			}
		});
		return discoveryCache.computeIfAbsent(Map.copyOf(key), unused -> patch(sources, null, null, name -> true, null, true, Int2IntMaps.EMPTY_MAP));
	}

	public static PatchedShader patch(Map<PatchShaderType, String> sources, SamplerBindingPlan samplers,
		ColorAttachmentPlan.Program outputs, Predicate<String> registered) {
		return patch(sources, samplers, outputs, registered, UniformPlan.LOCAL, true, Int2IntMaps.EMPTY_MAP);
	}

	public static PatchedShader patch(Map<PatchShaderType, String> sources, SamplerBindingPlan samplers,
		ColorAttachmentPlan.Program outputs, Predicate<String> registered, UniformPlan uniforms,
		boolean vulkan, Int2IntMap buffers) {

		var trees = new EnumMap<PatchShaderType, TranslationUnit>(PatchShaderType.class);

		for (var entry : sources.entrySet()) {
			if (entry.getValue() != null) {
				trees.put(entry.getKey(), parser.parseSeparateTranslationUnit(entry.getValue()));
			}
		}

		trees.forEach((stage, tree) -> {
			removeUnreachableFunctions(tree);

			if (vulkan) {
				CompatibilityTransformer.transformVulkan(tree.getRoot());
			}

			for (var declaration : tree.getRoot().nodeIndex.getStream(TypeAndInitDeclaration.class).toList()) {
				var qualifier = declaration.getType().getTypeQualifier();
				boolean input = stage != PatchShaderType.VERTEX && Glsl.hasStorage(qualifier, StorageQualifier.StorageType.IN);
				boolean opaqueUniform = Glsl.hasStorage(qualifier, StorageQualifier.StorageType.UNIFORM) && declaration.getType()
					.getTypeSpecifier() instanceof BuiltinFixedTypeSpecifier opaque && (opaque.type.kind == BuiltinFixedTypeSpecifier.BuiltinType.TypeKind.SAMPLER || opaque.type.kind == BuiltinFixedTypeSpecifier.BuiltinType.TypeKind.IMAGE);

				if (!input && !opaqueUniform) {
					continue;
				}

				for (var member : new ArrayList<>(declaration.getMembers())) {
					String name = member.getName().getName();
					boolean used = tree.getRoot().identifierIndex.getStream(name).map(id -> id.getParent())
						.filter(ReferenceExpression.class::isInstance).map(ReferenceExpression.class::cast)
						.anyMatch(reference -> !Glsl.isShadowed(reference, name));

					if (!name.startsWith("gl_") && !used) {
						member.detachAndDelete();
					}
				}

				if (declaration.getMembers().isEmpty()) {
					declaration.getParent().detachAndDelete();
				}
			}
		});
		if (vulkan) {
			removeUnusedVertexOutputs(trees);
		}
		LayoutTransformer.transformGrouped(parser, trees, null, vulkan);

		BitSet written = outputs == null ? null : remapFragmentOutputs(parser, trees.get(PatchShaderType.FRAGMENT), outputs);
		ShaderResourceLayout layout = VulkanResourceTransformer.transform(parser, trees, registered, uniforms, vulkan);

		if (vulkan && samplers != null) {
			layout = lowerStorageBufferHeap(parser, trees, layout, samplers.vulkanHeap().buffers(), buffers);
			layout = lowerVulkanSamplerHeap(parser, trees, layout, samplers);
		}

		if (vulkan && uniforms != null) {
			var nativeBlock = trees.values().stream().filter(Objects::nonNull).flatMap(tree -> tree.getRoot().nodeIndex
				.getStream(InterfaceBlockDeclaration.class)).filter(block -> Glsl.layout(block.getTypeQualifier(), "push_constant") != null)
				.findFirst().orElse(null);
			String nativeMembers = nativeBlock == null ? null : nativeBlock.getStructBody().getMembers().stream()
				.map(member -> ASTPrinter.print(PrintType.COMPACT, member)).collect(java.util.stream.Collectors.joining());
			String pushMembers = VulkanPushConstants.members(layout, uniforms.profile(), nativeMembers);
			for (var tree : trees.values()) {
				if (tree == null) continue;
				var root = tree.getRoot();
				root.indexBuildSession(() -> {
					var stageNativeBlock = root.nodeIndex.getStream(InterfaceBlockDeclaration.class)
						.filter(block -> Glsl.layout(block.getTypeQualifier(), "push_constant") != null).findFirst().orElse(null);
					var draw = (DeclarationExternalDeclaration) parser.parseExternalDeclaration(root,
						"layout(push_constant, std430) uniform iris_Draw {" + pushMembers + "};");
					if (stageNativeBlock != null) {
						stageNativeBlock.getParent().detachAndDelete();
					}
					tree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS, draw);
				});
			}
		}

		CompatibilityTransformer.transformLate(parser, trees, vulkan);

		var result = new EnumMap<PatchShaderType, String>(PatchShaderType.class);

		for (var entry : trees.entrySet()) {
			result.put(entry.getKey(), ASTPrinter.print(PrintType.INDENTED, entry.getValue()));
		}

		return new PatchedShader(result, layout, samplers, written);
	}

	// this is needed for chocapic v6 which uses MORE THAN 128 COMPONENTS IF WE DON'T OPTIMIZE????
	private static void removeUnusedVertexOutputs(Map<PatchShaderType, TranslationUnit> trees) {
		var vertex = trees.get(PatchShaderType.VERTEX);
		var fragment = trees.get(PatchShaderType.FRAGMENT);

		if (vertex == null || fragment == null || trees.containsKey(PatchShaderType.TESS_CONTROL) || trees.containsKey(
			PatchShaderType.TESS_EVAL) || trees.containsKey(PatchShaderType.GEOMETRY)) {
			return;
		}

		var inputs = new HashSet<String>();

		for (var declaration : fragment.getRoot().nodeIndex.getStream(TypeAndInitDeclaration.class).toList()) {
			var qualifier = declaration.getType().getTypeQualifier();

			if (Glsl.hasStorage(qualifier, StorageType.IN)) {
				if (Glsl.layout(qualifier, "location") != null) {
					return;
				}

				declaration.getMembers().forEach(member -> inputs.add(member.getName().getName()));
			}
		}

		if (fragment.getRoot().nodeIndex.getStream(InterfaceBlockDeclaration.class)
			.anyMatch(block -> Glsl.hasStorage(block.getTypeQualifier(), StorageType.IN))) {
			return;
		}

		var root = vertex.getRoot();
		root.indexBuildSession(() -> {
			for (var external : List.copyOf(vertex.getChildren())) {
				if (!(external instanceof DeclarationExternalDeclaration declaration) || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration typed)) {
					continue;
				}

				var qualifier = typed.getType().getTypeQualifier();

				if (!Glsl.hasStorage(qualifier, StorageType.OUT) || !(typed.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier) || Glsl.layout(
					qualifier, "location") != null || Glsl.layout(qualifier, "xfb_buffer") != null || Glsl.layout(qualifier, "xfb_offset") != null) {
					continue;
				}

				for (var member : List.copyOf(typed.getMembers())) {
					String name = member.getName().getName();

					if (name.startsWith("gl_") || inputs.contains(name)) {
						continue;
					}

					var local = new TypeAndInitDeclaration(typed.getType().cloneInto(root), Stream.of(member.cloneInto(root)));

					for (var part : List.copyOf(local.getType().getTypeQualifier().getParts())) {
						if (!(part instanceof PrecisionQualifier || part instanceof PreciseQualifier)) {
							part.detachAndDelete();
						}
					}

					vertex.getChildren().add(vertex.getChildren().indexOf(declaration), new DeclarationExternalDeclaration(local));
					member.detachAndDelete();
				}

				if (typed.getMembers().isEmpty()) {
					declaration.detachAndDelete();
				}
			}
		});
	}

	private static void removeUnreachableFunctions(TranslationUnit tree) {
		var calls = new HashMap<String, Set<String>>();
		var pending = new ArrayDeque<String>();
		pending.add("main");

		for (var call : tree.getRoot().nodeIndex.getStream(FunctionCallExpression.class).toList()) {
			if (call.getFunctionName() == null) {
				continue;
			}

			String name = call.getFunctionName().getName();
			var function = call.getAncestor(FunctionDefinition.class);

			if (function == null) {
				pending.add(name);
			} else {
				calls.computeIfAbsent(function.getFunctionPrototype().getName().getName(), ignored -> new HashSet<>()).add(name);
			}
		}

		var reachable = new HashSet<String>();

		while (!pending.isEmpty()) {
			String name = pending.removeFirst();

			if (reachable.add(name)) {
				pending.addAll(calls.getOrDefault(name, Set.of()));
			}
		}

		for (var function : tree.getRoot().nodeIndex.getStream(FunctionDefinition.class).toList()) {
			if (!reachable.contains(function.getFunctionPrototype().getName().getName())) {
				function.detachAndDelete();
			}
		}

		for (var declaration : tree.getRoot().nodeIndex.getStream(FunctionDeclaration.class).toList()) {
			if (!reachable.contains(declaration.getFunctionPrototype().getName().getName())) {
				declaration.getParent().detachAndDelete();
			}
		}
	}

	private static BitSet remapFragmentOutputs(ASTParser parser, TranslationUnit tree, ColorAttachmentPlan.Program plan) {
		var written = new BitSet(plan.attachmentCount());

		if (tree == null) {
			return written;
		}

		var root = tree.getRoot();
		var copies = new StringBuilder();
		root.indexBuildSession(() -> {
			for (var external : List.copyOf(tree.getChildren())) {
				if (!(external instanceof DeclarationExternalDeclaration declaration) || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration typed)) {
					continue;
				}

				var qualifier = typed.getType().getTypeQualifier();

				if (!Glsl.hasStorage(qualifier, StorageQualifier.StorageType.OUT)) {
					continue;
				}

				if (typed.getMembers().stream().allMatch(m -> m.getName().getName().startsWith("gl_"))) {
					continue;
				}

				if (typed.getMembers().size() != 1) {
					throw new IllegalArgumentException("split output declarations before remapping");
				}

				var member = typed.getMembers().getFirst();

				if (!(typed.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier numeric) || numeric.type.isMatrix()) {
					throw new IllegalArgumentException("unsupported fragment output type: " + member.getName().getName());
				}

				var index = Glsl.layout(qualifier, "index");

				if (index != null && Glsl.integer(index.getExpression(), root) != 0) {
					throw new IllegalArgumentException("dual-source fragment outputs require a separate pipeline");
				}

				var location = Glsl.layout(qualifier, "location");

				if (location == null) {
					throw new IllegalArgumentException("allocate output locations before remapping");
				}

				int first = Glsl.integer(location.getExpression(), root);
				var dimensions = Glsl.dimensions(root, member.getArraySpecifier(), numeric.getArraySpecifier());

				if (dimensions.size() > 1) {
					throw new IllegalArgumentException("multidimensional fragment output arrays are unsupported");
				}

				int count = dimensions.isEmpty() ? 1 : dimensions.getFirst();
				int mapped = plan.attachment(first);

				if (dimensions.isEmpty() && mapped >= 0) {
					Glsl.setLayout(qualifier, "location", mapped);
					written.set(mapped);
					continue;
				}

				var outputQualifier = qualifier.cloneInto(root);

				for (var part : List.copyOf(qualifier.getParts())) {
					if (!(part instanceof PrecisionQualifier || part instanceof PreciseQualifier)) {
						part.detachAndDelete();
					}
				}

				for (int i = 0; i < count; i++) {
					int attachment = plan.attachment(Math.addExact(first, i));

					if (attachment < 0) {
						continue;
					}

					String name = "iris_vkColorOutput" + attachment;

					if (root.identifierIndex.has(name)) {
						throw new IllegalArgumentException("reserved fragment output name: " + name);
					}

					var scalar = numeric.cloneInto(root);

					if (scalar.getArraySpecifier() != null) {
						scalar.getArraySpecifier().detachAndDelete();
					}

					Glsl.setLayout(outputQualifier, "location", attachment);
					tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
						ASTPrinter.print(PrintType.COMPACT, outputQualifier) + " " + ASTPrinter.print(PrintType.COMPACT,
							scalar) + " " + name + ";");
					copies.append(name).append('=').append(member.getName().getName());

					if (!dimensions.isEmpty()) {
						copies.append('[').append(i).append(']');
					}

					copies.append(';');
					written.set(attachment);
				}
			}

			if (!copies.isEmpty()) {
				if (root.identifierIndex.has("iris_vkFragmentMain")) {
					throw new IllegalArgumentException("reserved fragment entry point");
				}

				root.rename("main", "iris_vkFragmentMain");
				tree.getChildren().add(parser.parseExternalDeclaration(root, "void main(){iris_vkFragmentMain();" + copies + "}"));
			}
		});

		return written;
	}

	private static ShaderResourceLayout lowerStorageBufferHeap(ASTParser parser, Map<PatchShaderType, TranslationUnit> trees,
		ShaderResourceLayout resources, int capacity, Int2IntMap indices) {
		var descriptors = resources.descriptors().stream().collect(java.util.stream.Collectors.toMap(
			ShaderResourceLayout.Descriptor::name, descriptor -> descriptor));
		for (var tree : trees.values()) {
			if (tree == null) continue;
			var root = tree.getRoot();
			root.indexBuildSession(() -> {
				for (var block : root.nodeIndex.getStream(InterfaceBlockDeclaration.class).toList()) {
					if (!Glsl.hasStorage(block.getTypeQualifier(), StorageType.BUFFER)) continue;
					var descriptor = descriptors.get(block.getBlockName().getName());
					int first = descriptor.originalBinding() == null ? 0 : descriptor.originalBinding();
					var dimensions = Glsl.dimensions(root, block.getArraySpecifier());
					String heap = "iris_heap_ssbo_" + block.getBlockName().getName();
					if (block.getVariableName() == null) {
						for (var member : block.getStructBody().getMembers()) {
							for (var declarator : member.getDeclarators()) {
								String name = declarator.getName().getName();
								for (var id : root.identifierIndex.getStream(name).toList()) {
									if (id.getParent() instanceof ReferenceExpression reference && !Glsl.isShadowed(reference, name)) {
										reference.replaceByAndDelete(parser.parseExpression(root, heap + "[" + indices.get(first) + "]." + name));
									}
								}
							}
						}
					} else {
						String name = block.getVariableName().getName();
						for (var id : root.identifierIndex.getStream(name).toList()) {
							if (!(id.getParent() instanceof ReferenceExpression reference) || Glsl.isShadowed(reference, name)) continue;
							Expression access = reference;
							String element = "0";
							for (int dimension : dimensions) {
								if (access.getParent() instanceof io.github.douira.glsl_transformer.ast.node.expression.unary.LengthAccessExpression length) {
									length.replaceByAndDelete(parser.parseExpression(root, String.valueOf(dimension)));
									access = null;
									break;
								}
								var array = (ArrayAccessExpression) access.getParent();
								element = "(" + element + " * " + dimension + " + int(" + ASTPrinter.print(PrintType.COMPACT, array.getRight()) + "))";
								access = array;
							}
							if (access == null) continue;
							String index = dimensions.isEmpty() ? String.valueOf(indices.get(first)) : "(int[" + descriptor.count() + "](" +
								java.util.stream.IntStream.range(0, descriptor.count()).mapToObj(i -> String.valueOf(indices.get(first + i)))
									.collect(java.util.stream.Collectors.joining(",")) + "))[" + element + "]";
							access.replaceByAndDelete(parser.parseExpression(root, heap + "[" + index + "]"));
						}
					}
					block.setVariableName(new io.github.douira.glsl_transformer.ast.node.Identifier(heap));
					block.setArraySpecifier(new io.github.douira.glsl_transformer.ast.node.type.specifier.ArraySpecifier(
						Stream.of(parser.parseExpression(root, String.valueOf(capacity)))));
					Glsl.setLayout(block.getTypeQualifier(), "set", VulkanHeapLayout.DESCRIPTOR_SET);
					Glsl.setLayout(block.getTypeQualifier(), "binding", VulkanHeapLayout.BUFFERS_BINDING);
				}
			});
		}
		return new ShaderResourceLayout(resources.uniformBufferSize(), resources.uniforms(), resources.descriptors().stream()
			.map(descriptor -> descriptor.kind() == ShaderResourceLayout.Kind.STORAGE_BUFFER
				? new ShaderResourceLayout.Descriptor(descriptor.name(), descriptor.kind(), descriptor.type(), capacity,
					VulkanHeapLayout.DESCRIPTOR_SET, VulkanHeapLayout.BUFFERS_BINDING, descriptor.originalBinding(), descriptor.stages(), descriptor.access()) : descriptor)
			.toList(), resources.pushUniforms());
	}

	private static ShaderResourceLayout lowerVulkanSamplerHeap(ASTParser parser, Map<PatchShaderType, TranslationUnit> trees,
		ShaderResourceLayout resources, SamplerBindingPlan plan) {
		Map<SamplerHeapLayout.Type, Set<PatchShaderType>> heapStages = new EnumMap<>(SamplerHeapLayout.Type.class);
		var programSlots = plan.slots().stream().filter(slot -> !plan.heap().isDraw(slot)).toList();
		Map<SamplerHeapLayout.Type, Set<PatchShaderType>> drawStages = new EnumMap<>(SamplerHeapLayout.Type.class);
		var drawSlots = plan.slots().stream().filter(plan.heap()::isDraw).toList();



		for (var entry : trees.entrySet()) {
			TranslationUnit tree = entry.getValue();

			if (tree == null) {
				continue;
			}

			var root = tree.getRoot();
			Set<SamplerHeapLayout.Type> used = EnumSet.noneOf(SamplerHeapLayout.Type.class);
			root.indexBuildSession(() -> {
				for (var external : List.copyOf(tree.getChildren())) {
					if (!(external instanceof DeclarationExternalDeclaration declaration) || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration variables) || !Glsl.hasStorage(
						variables.getType().getTypeQualifier(), StorageType.UNIFORM) || !(variables.getType()
						.getTypeSpecifier() instanceof BuiltinFixedTypeSpecifier opaque) || opaque.type.kind != BuiltinFixedTypeSpecifier.BuiltinType.TypeKind.SAMPLER) {
						continue;
					}

					SamplerHeapLayout.Type type;

					try {
						type = SamplerHeapLayout.Type.valueOf(opaque.type.name());
					} catch (IllegalArgumentException exception) {
						throw new IllegalArgumentException("unsupported sampler heap type: " + opaque.type, exception);
					}

					for (var member : variables.getMembers()) {
						String name = member.getName().getName();
						var dimensions = Glsl.dimensions(root, member.getArraySpecifier(), opaque.getArraySpecifier());
						int count = dimensions.stream().reduce(1, Math::multiplyExact);
						var slots = plan.require(name, type, count);
						var references = root.identifierIndex.get(name).stream().filter(id -> id.getParent() instanceof ReferenceExpression)
							.map(id -> (ReferenceExpression) id.getParent()).toList();

						for (var reference : references) {
							if (Glsl.isShadowed(reference, name)) {
								continue;
							}

							Expression access = reference;
							String element = "0u";
							boolean wholeArray = !dimensions.isEmpty() && !(access.getParent() instanceof ArrayAccessExpression);
							if (wholeArray && dimensions.size() != 1) throw new IllegalArgumentException("multidimensional sampler array arguments are not supported: " + name);
							if (!wholeArray) {
								for (int dimension : dimensions) {
									if (!(access.getParent() instanceof ArrayAccessExpression array) || array.getLeft() != access) {
										throw new IllegalArgumentException("sampler arrays must be fully indexed: " + name);
									}
									element = "(" + element + " * " + dimension + "u + uint(" + ASTPrinter.print(PrintType.COMPACT, array.getRight()) + "))";
									access = array;
								}
							}
							String records = slots.stream().map(slot -> plan.heap().isDraw(slot)
								? "uvec2(" + drawSlots.stream().filter(draw -> draw.type() == slot.type()).toList().indexOf(slot) + "u,4294967295u)"
								: plan.descriptor(slot).expression())
								.collect(java.util.stream.Collectors.joining(","));
							String record = dimensions.isEmpty() ? records : "uvec2[" + count + "](" + records + ")";
							if (!wholeArray && !dimensions.isEmpty()) record = "(" + record + ")[" + element + "]";
							access.replaceByAndDelete(parser.parseExpression(root, type.uniformName() + "[" + record + "]"));
							used.add(type);
						}
					}

					declaration.detachAndDelete();
				}

				var functions = lowerSamplerParameters(parser, tree, used);
				if (!used.isEmpty()) {
					rejectSamplerSelections(tree, used, Set.of());
					lowerSamplerArguments(parser, tree, functions);
					removeUnreachableFunctions(tree);
					Set<SamplerHeapLayout.Type> declaredImages = EnumSet.noneOf(SamplerHeapLayout.Type.class);
					for (var type : used) {
						// SPIRV-Cross marks a whole image variable as depth when used for comparison.
						// Keep shadow and color arrays distinct while aliasing the same descriptor binding.
						// Otherwise, there will be a lot of macOS corruption.
						var imageType = type;
						boolean hasStatic = programSlots.stream().anyMatch(slot -> slot.type() == type);
						var typeDraws = drawSlots.stream().filter(slot -> slot.type() == type).toList();
						for (var id : root.identifierIndex.getStream(type.uniformName())
							.sorted(java.util.Comparator.comparingInt((io.github.douira.glsl_transformer.ast.node.Identifier id) -> nodeDepth(id)).reversed()).toList()) {
							if (!(id.getParent() instanceof ReferenceExpression ref) || !(ref.getParent() instanceof ArrayAccessExpression access)) continue;
							var literal = constantSamplerRecord(access.getRight());
							boolean constant = literal != null;
							String record = constant ? null : "(" + ASTPrinter.print(PrintType.COMPACT, access.getRight()) + ")";
							String imageIndex = constant ? literal[0] + "u" : record + ".x";
							String samplerIndex = constant ? literal[1] + "u" : record + ".y";
							boolean drawConstant = constant && literal[1] == 0xffff_ffffL;
							if (!hasStatic || drawConstant) {
								access.replaceByAndDelete(parser.parseExpression(root, type.drawUniformName() + "[" + imageIndex + "]"));
								continue;
							}
							String image = imageType.uniformName() + "[" + imageIndex + "]";
							String combined = type.glsl + "(" + image + ", "
								+ (type.glsl.endsWith("Shadow") ? "iris_heapShadowSamplers" : "iris_heapSamplers") + "[" + samplerIndex + "])";
							if (typeDraws.isEmpty() || constant) access.replaceByAndDelete(parser.parseExpression(root, combined));
							else {
								var call = access.getAncestor(FunctionCallExpression.class);
								if (call == null || call.getFunctionName() == null || heapAccess(call.getParameters().get(0), Set.of(type.uniformName())) == null) {
									throw new IllegalArgumentException("sampler must be used directly by a texture operation");
								}
								String arguments = call.getParameters().stream().skip(1).map(arg -> ASTPrinter.print(PrintType.COMPACT, arg))
									.collect(java.util.stream.Collectors.joining(","));
								if (!arguments.isEmpty()) arguments = "," + arguments;
								String function = call.getFunctionName().getName();
								call.replaceByAndDelete(parser.parseExpression(root, record + ".y == 4294967295u ? " + function + "("
									+ type.drawUniformName() + "[" + record + ".x]" + arguments + ") : " + function + "(" + combined + arguments + ")"));
							}
						}
						if (hasStatic) {
							if (declaredImages.add(imageType)) {
								String textureType = type.glsl.replace("sampler", "texture").replace("Shadow", "");
								tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
									"layout(set = " + VulkanHeapLayout.DESCRIPTOR_SET + ", binding = " + VulkanHeapLayout.IMAGES_BINDING
										+ ") uniform " + textureType + " " + imageType.uniformName() + "["
										+ plan.vulkanHeap().images() + "];");
							}
							heapStages.computeIfAbsent(imageType, ignored -> EnumSet.noneOf(PatchShaderType.class)).add(entry.getKey());
						}
						if (!typeDraws.isEmpty()) {
							tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
								"layout(set = " + SamplerHeapLayout.DRAW_DESCRIPTOR_SET + ", binding = " + type.binding() + ") uniform " + type.glsl + " " + type.drawUniformName() + "[" + typeDraws.size() + "];");
							drawStages.computeIfAbsent(type, ignored -> EnumSet.noneOf(PatchShaderType.class)).add(entry.getKey());
						}
					}
					if (used.stream().filter(type -> programSlots.stream().anyMatch(slot -> slot.type() == type)).anyMatch(type -> !type.glsl.endsWith("Shadow"))) {
						tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
							"layout(set = " + VulkanHeapLayout.DESCRIPTOR_SET + ", binding = " + VulkanHeapLayout.SAMPLERS_BINDING + ") uniform sampler iris_heapSamplers[" + plan.vulkanHeap().samplers() + "];");
					}
					if (used.stream().filter(type -> programSlots.stream().anyMatch(slot -> slot.type() == type)).anyMatch(type -> type.glsl.endsWith("Shadow"))) {
						tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
							"layout(set = " + VulkanHeapLayout.DESCRIPTOR_SET + ", binding = " + VulkanHeapLayout.SAMPLERS_BINDING + ") uniform samplerShadow iris_heapShadowSamplers[" + plan.vulkanHeap().samplers() + "];");
					}


				}
			});
		}

		List<ShaderResourceLayout.Descriptor> descriptors = new ArrayList<>();

		for (var descriptor : resources.descriptors()) {
			if (descriptor.kind() != ShaderResourceLayout.Kind.SAMPLER) {
				descriptors.add(descriptor);
			}
		}


		heapStages.forEach((type, stages) -> descriptors.add(
			new ShaderResourceLayout.Descriptor(type.uniformName(), ShaderResourceLayout.Kind.SAMPLER, type.name(),
				plan.vulkanHeap().images(), SamplerHeapLayout.DESCRIPTOR_SET, VulkanHeapLayout.IMAGES_BINDING, null, stages)));

		drawStages.forEach((type, stages) -> descriptors.add(new ShaderResourceLayout.Descriptor(type.drawUniformName(),
			ShaderResourceLayout.Kind.SAMPLER, type.name(), (int) drawSlots.stream().filter(slot -> slot.type() == type).count(),
			SamplerHeapLayout.DRAW_DESCRIPTOR_SET, type.binding(), null, stages)));

		return new ShaderResourceLayout(resources.uniformBufferSize(), resources.uniforms(), descriptors, resources.pushUniforms());
	}

	private static long[] constantSamplerRecord(Expression expression) {
		while (expression instanceof GroupingExpression grouping) {
			expression = grouping.getOperand();
		}
		if (!(expression instanceof FunctionCallExpression call)
			|| !(call.getFunctionSpecifier() instanceof BuiltinNumericTypeSpecifier type)
			|| type.type != Type.U32VEC2 || type.getArraySpecifier() != null || call.getParameters().size() != 2) {
			return null;
		}
		var indices = new long[2];
		for (int i = 0; i < indices.length; i++) {
			Expression component = call.getParameters().get(i);
			while (component instanceof GroupingExpression grouping) {
				component = grouping.getOperand();
			}
			if (!(component instanceof LiteralExpression literal) || !literal.isInteger()
				|| literal.getInteger() < 0 || literal.getInteger() > 0xffff_ffffL) {
				return null;
			}
			indices[i] = literal.getInteger();
		}
		return indices;
	}

	private static int nodeDepth(ASTNode node) {
		int depth = 0;
		while ((node = node.getParent()) != null) depth++;
		return depth;
	}

	public record SamplerFunction(String name, String lowered, Int2ObjectMap<SamplerHeapLayout.Type> parameters, int count) { }

	private static List<SamplerFunction> lowerSamplerParameters(ASTParser parser, TranslationUnit tree, Set<SamplerHeapLayout.Type> used) {
		var root = tree.getRoot();
		List<SamplerFunction> functions = new ArrayList<>();
		for (var prototype : root.nodeIndex.getStream(FunctionPrototype.class).toList()) {
			Int2ObjectMap<SamplerHeapLayout.Type> parameters = new Int2ObjectLinkedOpenHashMap<>();
			for (int i = 0; i < prototype.getParameters().size(); i++) {
				var parameter = prototype.getParameters().get(i);
				if (!(parameter.getType().getTypeSpecifier() instanceof BuiltinFixedTypeSpecifier opaque)
					|| opaque.type.kind != BuiltinFixedTypeSpecifier.BuiltinType.TypeKind.SAMPLER) continue;
				var type = SamplerHeapLayout.Type.valueOf(opaque.type.name());
				parameters.put(i, type);
				used.add(type);
				var function = prototype.getAncestor(FunctionDefinition.class);
				if (function != null && parameter.getName() != null) {
					String name = parameter.getName().getName();
					for (var identifier : root.identifierIndex.getStream(name).toList()) {
						if (!(identifier.getParent() instanceof ReferenceExpression reference)
							|| reference.getAncestor(FunctionDefinition.class) != function || Glsl.isShadowed(reference, name, parameter)) continue;
						Expression access = reference;
						while (access.getParent() instanceof ArrayAccessExpression array && array.getLeft() == access) access = array;
						String index = ASTPrinter.print(PrintType.COMPACT, access);
						access.replaceByAndDelete(parser.parseExpression(root, type.uniformName() + "[" + index + "]"));
					}
				}
				parameter.getType().setTypeSpecifier(new BuiltinNumericTypeSpecifier(Type.U32VEC2));
			}
			if (!parameters.isEmpty()) {
				String name = prototype.getName().getName();
				String lowered = "iris_heapFunction_" + name + parameters.int2ObjectEntrySet().stream()
					.map(entry -> "_" + entry.getIntKey() + "_" + entry.getValue().name()).collect(java.util.stream.Collectors.joining());
				functions.add(new SamplerFunction(name, lowered, Int2ObjectMaps.unmodifiable(parameters), prototype.getParameters().size()));
				prototype.getName().setName(lowered);
			}
		}
		return functions;
	}

	private static void lowerSamplerArguments(ASTParser parser, TranslationUnit tree, List<SamplerFunction> functions) {
		for (var call : tree.getRoot().nodeIndex.getStream(FunctionCallExpression.class).toList()) {
			if (call.getFunctionName() == null) continue;
			for (var function : functions) {
				if (!call.getFunctionName().getName().equals(function.name()) || call.getParameters().size() != function.count()) continue;
				boolean matches = function.parameters().int2ObjectEntrySet().stream().allMatch(entry ->
					heapAccess(call.getParameters().get(entry.getIntKey()), Set.of(entry.getValue().uniformName())) != null);
				if (!matches) continue;
				for (var entry : function.parameters().int2ObjectEntrySet()) {
					var argument = call.getParameters().get(entry.getIntKey());
					var access = heapAccess(argument, Set.of(entry.getValue().uniformName()));
					argument.replaceByAndDelete(parser.parseExpression(tree.getRoot(), access.index()));
				}
				call.getFunctionName().setName(function.lowered());
				break;
			}
		}
	}

	private static void rejectSamplerSelections(TranslationUnit tree, Set<SamplerHeapLayout.Type> used,
		Set<SamplerHeapLayout.Type> usedDraw) {
		var heaps = new HashSet<String>();
		used.forEach(type -> heaps.add(type.uniformName()));
		usedDraw.forEach(type -> heaps.add(type.drawUniformName()));
		for (var conditional : tree.getRoot().nodeIndex.getStream(ConditionExpression.class).toList()) {
			if (heapAccess(conditional.getSecond(), heaps) != null || heapAccess(conditional.getThird(), heaps) != null) {
				throw new IllegalArgumentException("conditional expressions cannot select opaque sampler values");
			}
		}
	}

	private static HeapAccess heapAccess(Expression expression, Set<String> heaps) {
		while (expression instanceof GroupingExpression grouping) {
			expression = grouping.getOperand();
		}

		if (!(expression instanceof ArrayAccessExpression access) || !(access.getLeft() instanceof ReferenceExpression reference) || !heaps.contains(
			reference.getIdentifier().getName())) {
			return null;
		}

		return new HeapAccess(reference.getIdentifier().getName(), ASTPrinter.print(PrintType.COMPACT, access.getRight()));
	}

	public record HeapAccess(String heap, String index) {
	}

}
