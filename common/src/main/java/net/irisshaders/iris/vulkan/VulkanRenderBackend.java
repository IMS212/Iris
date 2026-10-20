package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass.RenderArea;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.ComputePipeline;
import net.irisshaders.iris.backend.GraphicsShader;
import net.irisshaders.iris.backend.IrisGpuFormats;
import net.irisshaders.iris.backend.IrisImage;
import net.irisshaders.iris.backend.NonRenderCommands;
import net.irisshaders.iris.backend.PackResources;
import net.irisshaders.iris.backend.RenderBackend;
import net.irisshaders.iris.backend.RenderGraph;
import net.irisshaders.iris.backend.WorldPassCommands;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.mixinterface.VulkanWorldPassAccess;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.samplers.PackSamplers;
import net.irisshaders.iris.samplers.ProgramResources;
import net.irisshaders.iris.shaderpack.FilledIndirectPointer;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.uniforms.buffer.DrawUniforms;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFormatProperties;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class VulkanRenderBackend implements RenderBackend, NonRenderCommands {
	public final VulkanTextureState textures = new VulkanTextureState(this);
	private final VulkanColorMipmaps mipmaps = new VulkanColorMipmaps();
	private final VulkanDepthMipmaps depthMipmaps = new VulkanDepthMipmaps();
	private final Map<GpuFormat, GpuFormat> renderTargetFormats = new EnumMap<>(GpuFormat.class);
	private final VulkanRenderPassCache renderPassCache = new VulkanRenderPassCache(VulkanCommandBridge.getDevice().vkDevice());
	private VulkanShaderCache shaderCache;
	public VulkanSamplerHeap activeHeap;
	private volatile boolean closed;

	public VulkanRenderBackend() {
		((VulkanWorldPassAccess) VulkanCommandBridge.getDevice().createCommandEncoder()).iris$setTextureState(this.textures);
	}

	@Override
	public WorldPassCommands createRenderPass(RenderPassDescriptor descriptor) {
		return new VulkanWorldCommands(this.textures.takeClears(descriptor));
	}

	@Override
	public WorldPassCommands adaptRenderPass(RenderPassBackend pass, RenderArea area) {
		return new VulkanWorldCommands(pass, false);
	}

	@Override
	public long textureId(GpuTexture texture) {
		return ((VulkanGpuTexture) texture).vkImage();
	}

	@Override
	public long bufferId(GpuBuffer buffer) {
		return ((VulkanGpuBuffer) buffer).vkBuffer();
	}

	@Override
	public Runnable barrier(RenderGraph.Dependency dependency) {
		return VulkanSynchronization.compile(dependency);
	}

	@Override
	public Runnable worldBarrier() {
		return VulkanSynchronization.GRAPHICS_IN;
	}

	@Override
	public NonRenderCommands commands() {
		return this;
	}

	@Override
	public void dispatch(ComputePipeline pipeline, Vector3i groups, FilledIndirectPointer indirect) {
		this.textures.flush();
		((VulkanComputePipeline) pipeline).dispatch(groups, indirect);
	}

	@Override
	public PackResources createPackResources(PackSamplers pack) {
		return new VulkanPackResources(pack);
	}

	@Override
	public GraphicsShader createGraphicsShader(String name, PatchedShader source, ProgramResources resources,
		DrawUniforms uniforms) {
		return new VulkanGraphicsShader(this.getShaderCache(), name, source, resources, uniforms);
	}

	@Override
	public ComputePipeline createComputePipeline(String name, PatchedShader source, ProgramResources resources,
		DrawUniforms uniforms) {
		return new VulkanComputePipeline(this.getShaderCache(), name, source, resources, uniforms);
	}

	@Override
	public void finishCompilation() {
		if (this.shaderCache != null) {
			this.shaderCache.finishCompilation();
		}
	}

	private VulkanShaderCache getShaderCache() {
		if (this.shaderCache == null) {
			this.shaderCache = new VulkanShaderCache(VulkanCommandBridge.getDevice().vkDevice());
		}

		return this.shaderCache;
	}

	public VulkanRenderPassCache getRenderPassCache() {
		return this.renderPassCache;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		if (this.shaderCache != null) {
			this.shaderCache.finishCompilation();
		}

		this.textures.flush();
		this.closed = true;
		this.mipmaps.close();
		this.depthMipmaps.close();

		if (this.shaderCache != null) {
			this.retire(this.shaderCache::close);
		}

		this.renderPassCache.close();
	}

	@Override
	public IrisImage createImage(String name, TextureType type, GpuFormat format, int width, int height, int depth, boolean storage) {
		return new VulkanImage(VulkanCommandBridge.getDevice(), name, type, format, width, height, depth, storage);
	}

	@Override
	public GpuBuffer createStorageBuffer(String name, long size, byte[] content) {
		return new VulkanStorageBuffer(VulkanCommandBridge.getDevice(), name, size, content);
	}

	@Override
	public void retire(Runnable destructor) {
		VulkanCommandBridge.deleteWhenDone(destructor);
	}

	@Override
	public void generateMipmaps(GpuTexture texture, boolean linear) {
		if (texture.getMipLevels() <= 1) {
			return;
		}
		this.textures.beforeAccess(texture);
		var command = VulkanCommandBridge.commandBuffer();
		var debug = VulkanCommandBridge.getDevice().instance().debug();
		debug.beginDebugGroup(command, () -> "Mipmaps: " + texture.getLabel());
		try {
			if (!linear || !this.mipmaps.generate(texture, this.activeHeap)) {
				VulkanTextureOps.generateMipmaps(texture, linear);
			}
		} finally {
			debug.endDebugGroup(command);
		}
	}

	@Override
	public void copyTexture(GpuTexture source, GpuTexture destination, int width, int height) {
		VulkanTextureOps.copyTexture(source, destination, width, height);
	}

	@Override
	public List<RenderGraph.Access> mipmapAccesses(GpuTexture texture, boolean linear, PackSamplers pack) {
		boolean compute = linear && texture.getDepthOrLayers() == 1 && (texture.usage() & RenderTarget.USAGE_STORAGE) != 0
			&& this.mipmaps.supports(texture.getFormat());
		int stage = compute ? RenderGraph.Stage.COMPUTE.bit : RenderGraph.Stage.TRANSFER.bit;
		int usage = compute ? RenderGraph.Usage.SHADER.bit : RenderGraph.Usage.TRANSFER.bit;
		var accesses = new ArrayList<RenderGraph.Access>();
		accesses.add(new RenderGraph.Access(RenderGraph.image(texture), stage, usage, usage));
		if (compute) {
			var heap = ((VulkanPackResources) pack.getResources()).getSamplerHeap();
			accesses.add(new RenderGraph.Access(RenderGraph.buffer(heap.mipmapCounter()), stage, usage, usage));
		}
		return accesses;
	}

	@Override
	public List<RenderGraph.Access> depthMipmapAccesses(GpuTexture texture) {
		return List.of(new RenderGraph.Access(RenderGraph.image(texture), RenderGraph.Stage.DEPTH.bit | RenderGraph.Stage.FRAGMENT.bit,
			RenderGraph.Usage.DEPTH_ATTACHMENT.bit | RenderGraph.Usage.SHADER.bit, RenderGraph.Usage.DEPTH_ATTACHMENT.bit));
	}

	@Override
	public int getMipmapUsage(GpuFormat format) {
		return this.mipmaps.supports(format) ? RenderTarget.USAGE_STORAGE : 0;
	}

	@Override
	public GpuFormat getRenderTargetFormat(GpuFormat format) {
		return this.renderTargetFormats.computeIfAbsent(format, requested -> {
			try (var stack = MemoryStack.stackPush()) {
				var properties = VkFormatProperties.calloc(stack);
				VK10.vkGetPhysicalDeviceFormatProperties(VulkanCommandBridge.getDevice().vkDevice().getPhysicalDevice(),
					VulkanConst.toVk(requested), properties);
				if ((properties.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) != 0) {
					return requested;
				}
			}
			// Packed formats can support sampling without supporting color attachments.
			var replacement = requested == IrisGpuFormats.RGBA4_UNORM ? GpuFormat.RGBA8_UNORM
				: requested == IrisGpuFormats.RGB9E5_FLOAT ? GpuFormat.RGBA16_FLOAT : null;
			if (replacement == null) {
				throw new UnsupportedOperationException("Vulkan does not support render target format " + requested);
			}
			Iris.logger.warn("Vulkan does not support {} as a render target; using {}", requested, replacement);
			return replacement;
		});
	}

	public void onDeleteTexture(GpuTexture texture) {
		this.textures.release(texture);
		this.mipmaps.release(texture);
		this.depthMipmaps.release(texture);
	}

	@Override
	public void clearColors(List<GpuTextureView> textures, Vector4f color) {
		this.textures.clear(textures, color);
	}

	@Override
	public void generateDepthMips(GpuTexture texture) {
		if (texture.getMipLevels() <= 1) {
			return;
		}
		var command = VulkanCommandBridge.commandBuffer();
		var debug = VulkanCommandBridge.getDevice().instance().debug();
		debug.beginDebugGroup(command, () -> "Depth mipmaps: " + texture.getLabel());
		try {
			this.depthMipmaps.generate(texture, this.activeHeap);
		} finally {
			debug.endDebugGroup(command);
		}
	}
}
