package net.irisshaders.iris.opengl;

import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import it.unimi.dsi.fastutil.objects.Object2IntAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import net.irisshaders.iris.pipeline.transform.Glsl;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;

import java.util.EnumMap;

public class GlShaderPatcher {
	public static Result patch(PatchedShader input) {
		var bindings = getBindings(input.layout());

		var sources = new EnumMap<PatchShaderType, String>(PatchShaderType.class);
		var parser = new SingleASTTransformer<>();
		input.sources().forEach((stage, text) -> {
			var tree = parser.parseSeparateTranslationUnit(text);
			tree.getRoot().indexBuildSession(() -> {
				for (var qualifier : tree.getRoot().nodeIndex.getStream(TypeQualifier.class).toList()) {
					if (Glsl.layout(qualifier, "set") != null) {
						Glsl.removeLayout(qualifier, "set", "binding");
					}
				}
			});
			sources.put(stage, ASTPrinter.print(PrintType.INDENTED, tree));
		});

		return new Result(new PatchedShader(sources, input.layout(), null, input.writtenAttachments()), bindings);
	}

	public static Object2IntMap<String> getBindings(ShaderResourceLayout layout) {
		var bindings = new Object2IntLinkedOpenHashMap<String>();
		var counts = new Object2IntAVLTreeMap<ShaderResourceLayout.Kind>();
		for (var descriptor : layout.descriptors()) {
			int binding = counts.getOrDefault(descriptor.kind(), 0);
			counts.put(descriptor.kind(), binding + descriptor.count());
			bindings.put(descriptor.name(), binding);
		}
		return Object2IntMaps.unmodifiable(bindings);
	}

	public record Result(PatchedShader source, Object2IntMap<String> bindings) {
	}
}
