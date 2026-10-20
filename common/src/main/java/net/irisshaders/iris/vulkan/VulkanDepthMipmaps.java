package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanShaderPatcher;
import net.irisshaders.iris.samplers.SamplerBindingPlan;
import net.irisshaders.iris.samplers.SamplerHeapLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class VulkanDepthMipmaps implements AutoCloseable {
	private final Map<GpuTexture, List<GpuTextureView>> depthMipViews = new IdentityHashMap<>();
	private VulkanGraphicsPipeline depthMipPipeline;
	private SamplerHeapLayout.Slot depthMipSlot;

	private void initialize(VulkanSamplerHeap heap) {
		var backend = VulkanCommandBridge.getDevice();

		var sourceLayout = new SamplerHeapLayout.Builder().add(SamplerHeapLayout.Type.SAMPLER2D, "depth", "linear").build();
		var layout = new SamplerHeapLayout(Map.of(), sourceLayout.heaps());
		this.depthMipSlot = layout.slot(SamplerHeapLayout.Type.SAMPLER2D, "depth", "linear");

		// this is stupid??? why can't we just have good things
		var shader = VulkanShaderPatcher.patch(Map.of(PatchShaderType.VERTEX, """
				#version 450
				void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2.0-1.0,0,1);}""",
			PatchShaderType.FRAGMENT, """
				#version 450
				uniform sampler2D sourceDepth;
				void main() {
				    ivec2 size = max(textureSize(sourceDepth, 0) / 2, ivec2(1));
				    vec2 uv = gl_FragCoord.xy / vec2(size);
				    gl_FragDepth = textureLod(sourceDepth, uv, 0.0).r;
				}
				"""), new SamplerBindingPlan.Builder(layout).bind("sourceDepth", this.depthMipSlot).build());

		try (var compiler = new VulkanShaderCompiler()) {
			var vertex = compiler.compile("depth-mips.vsh", PatchShaderType.VERTEX, shader.sources().get(PatchShaderType.VERTEX));

			try {
				var fragment = compiler.compile("depth-mips.fsh", PatchShaderType.FRAGMENT, shader.sources().get(PatchShaderType.FRAGMENT));

				try {
					this.depthMipPipeline = new VulkanGraphicsPipeline(backend.vkDevice(), heap, vertex, fragment,
						VK10.VK_FORMAT_D32_SFLOAT, heap.drawLayout(shader.layout().descriptors()));
				} finally {
					MemoryUtil.memFree(fragment);
				}
			} finally {
				MemoryUtil.memFree(vertex);
			}
		}
	}

	public void generate(GpuTexture texture, VulkanSamplerHeap heap) {
		if (texture.getMipLevels() < 2) {
			return;
		}

		if (this.depthMipPipeline == null) {
			this.initialize(heap);
		}

		var levels = this.depthMipViews.computeIfAbsent(texture, image -> {
			var result = new ArrayList<GpuTextureView>();

			try {
				for (int i = 0; i < image.getMipLevels(); i++) {
					result.add(RenderSystem.getDevice().createTextureView(image, i, 1));
				}

				return result;
			} catch (RuntimeException | Error failure) {
				result.forEach(GpuTextureView::close);

				throw failure;
			}
		});
		var command = VulkanCommandBridge.commandBuffer();

		try (var stack = MemoryStack.stackPush()) {

			for (int level = 1; level < levels.size(); level++) {
				if (level > 1) VulkanSynchronization.DEPTH_STEP.emit(command, stack);
				var source = (VulkanGpuTextureView) levels.get(level - 1);
				var target = (VulkanGpuTextureView) levels.get(level);
				var bindings = heap.bindings(List.of(this.depthMipSlot), Map.of(this.depthMipSlot,
					new VulkanSamplerHeap.ImageSampler(source.vkImageView(), heap.sampler(GlSampler.LINEAR),
						VK10.VK_IMAGE_LAYOUT_GENERAL)));

				int width = Math.max(1, texture.getWidth(level));
				int height = Math.max(1, texture.getHeight(level));
				((VulkanRenderBackend) Iris.getRenderBackend()).getRenderPassCache().begin(command, stack, new int[0],
					VK10.VK_FORMAT_D32_SFLOAT, new long[]{target.vkImageView()}, width, height, VK10.VK_IMAGE_LAYOUT_GENERAL,
					VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE);

				try {
					VK10.vkCmdSetViewport(command, 0, VkViewport.calloc(1, stack).width(width).height(height).minDepth(0).maxDepth(1));

					var scissor = VkRect2D.calloc(1, stack);
					scissor.extent().set(width, height);
					VK10.vkCmdSetScissor(command, 0, scissor);
					bindings.bind(command, this.depthMipPipeline.layout(), VK10.VK_PIPELINE_BIND_POINT_GRAPHICS);
					this.depthMipPipeline.bind(command, null);
					VK10.vkCmdDraw(command, 3, 1, 0, 0);
				} finally {
					VK10.vkCmdEndRenderPass(command);
				}

			}
		}
	}

	public void release(GpuTexture texture) {
		var levels = this.depthMipViews.remove(texture);
		if (levels != null) {
			levels.forEach(GpuTextureView::close);
		}
	}

	@Override
	public void close() {
		this.depthMipViews.values().forEach(levels -> levels.forEach(GpuTextureView::close));
		this.depthMipViews.clear();

		if (this.depthMipPipeline == null) {
			return;
		}

		VulkanCommandBridge.deleteWhenDone(this.depthMipPipeline::close);
		this.depthMipPipeline = null;
	}

}
