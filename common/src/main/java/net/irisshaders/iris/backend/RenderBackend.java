package net.irisshaders.iris.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.samplers.PackSamplers;
import net.irisshaders.iris.samplers.ProgramResources;
import net.irisshaders.iris.uniforms.buffer.DrawUniforms;
import org.joml.Vector2i;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

public interface RenderBackend extends AutoCloseable {
	public WorldPassCommands createRenderPass(RenderPassDescriptor descriptor);

	public static CommandEncoderBackend commandEncoder() {
		return ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend();
	}

	WorldPassCommands adaptRenderPass(RenderPassBackend pass, RenderPass.RenderArea area);

	default IrisRenderPass createRenderPass(RenderPass pass) {
		return pass.iris$getIrisPass();
	}

	public default IrisRenderPass createRenderPass(String name, FramebufferDescription description, List<GpuTextureView> colors,
		GpuTextureView depth, Vector2i viewport) {
		description.validate(colors, depth, viewport);
		var builder = RenderPassDescriptor.builder(() -> name).withRenderArea(new RenderPass.RenderArea(0, 0, viewport.x, viewport.y));

		for (var color : colors) {
			builder.withColorAttachment(color, Optional.empty());
		}

		if (depth != null) {
			builder.withDepthAttachment(depth, OptionalDouble.empty());
		}

		return this.createRenderPass(builder.build());
	}

	NonRenderCommands commands();

	public long textureId(GpuTexture texture);

	public long bufferId(GpuBuffer buffer);

	public Runnable barrier(RenderGraph.Dependency dependency);

	public Runnable worldBarrier();

	public default List<RenderGraph.Access> mipmapAccesses(GpuTexture texture, boolean linear, PackSamplers pack) {
		return List.of(new RenderGraph.Access(RenderGraph.image(texture), RenderGraph.Stage.TRANSFER.bit,
			RenderGraph.Usage.TRANSFER.bit, RenderGraph.Usage.TRANSFER.bit));
	}

	public default List<RenderGraph.Access> depthMipmapAccesses(GpuTexture texture) {
		return List.of(new RenderGraph.Access(RenderGraph.image(texture), RenderGraph.Stage.TRANSFER.bit,
			RenderGraph.Usage.TRANSFER.bit, RenderGraph.Usage.TRANSFER.bit));
	}

	PackResources createPackResources(PackSamplers pack);

	GraphicsShader createGraphicsShader(String name, PatchedShader source, ProgramResources resources, DrawUniforms uniforms);

	ComputePipeline createComputePipeline(String name, PatchedShader source, ProgramResources resources, DrawUniforms uniforms);

	IrisImage createImage(String name, TextureType type, GpuFormat format, int width, int height, int depth, boolean storage);

	GpuBuffer createStorageBuffer(String name, long size, byte[] content);

	void retire(Runnable destructor);

	default void finishCompilation() {
	}

	default void resetBindings() {
	}

	default int getMipmapUsage(GpuFormat format) {
		return 0;
	}

	public default GpuFormat getRenderTargetFormat(GpuFormat format) {
		return format;
	}

	@Override
	default void close() {
	}

}
