package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.RenderGraph;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import static org.lwjgl.vulkan.KHRSynchronization2.*;

/** Barrier descriptions are compiled at pack load; recording a barrier does no hazard tracking. */
public class VulkanSynchronization {
	public static final long MEMORY = VK_ACCESS_2_MEMORY_READ_BIT_KHR | VK_ACCESS_2_MEMORY_WRITE_BIT_KHR;
	public static final long DEPTH = VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT_KHR | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT_KHR;
	public static final Dependency GRAPHICS_IN = new Dependency(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR, MEMORY,
		VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT_KHR, MEMORY);
	public static final Dependency GRAPHICS_OUT = new Dependency(VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT_KHR, MEMORY,
		VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR, MEMORY);
	public static final Dependency TRANSFER_IN = new Dependency(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR, MEMORY,
		VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_READ_BIT_KHR | VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR);
	public static final Dependency TRANSFER_OUT = new Dependency(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
		VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR, MEMORY);
	public static final Dependency CLEAR_IN = new Dependency(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR, MEMORY,
		VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR);
	public static final Dependency CLEAR_OUT = new Dependency(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR,
		VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR, MEMORY);
	public static final Dependency COMPUTE_STEP = new Dependency(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_WRITE_BIT_KHR,
		VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_READ_BIT_KHR | VK_ACCESS_2_SHADER_WRITE_BIT_KHR);
	public static final Dependency TRANSFER_STEP = new Dependency(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
		VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_READ_BIT_KHR | VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR);
	public static final Dependency DEPTH_STEP = new Dependency(DEPTH, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR,
		DEPTH | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT_KHR | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR | VK_ACCESS_2_SHADER_READ_BIT_KHR);

	public static Runnable compile(RenderGraph.Dependency dependency) {
		if (dependency.srcStages() == 0 || dependency.dstStages() == 0) return RenderGraph.NO_OP;
		return new Dependency(stages(dependency.srcStages()), accesses(dependency.srcWrites(), true), stages(dependency.dstStages()),
			accesses(dependency.dstReads(), false) | accesses(dependency.dstWrites(), true));
	}

	private static long stages(int mask) {
		if ((mask & RenderGraph.Stage.ALL.bit) != 0) return VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR;
		long stages = 0;
		for (var stage : RenderGraph.Stage.values()) {
			if ((mask & stage.bit) == 0) continue;
			stages |= switch (stage) {
				case VERTEX -> VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR;
				case TESS_CONTROL -> VK_PIPELINE_STAGE_2_TESSELLATION_CONTROL_SHADER_BIT_KHR;
				case TESS_EVAL -> VK_PIPELINE_STAGE_2_TESSELLATION_EVALUATION_SHADER_BIT_KHR;
				case GEOMETRY -> VK_PIPELINE_STAGE_2_GEOMETRY_SHADER_BIT_KHR;
				case FRAGMENT -> VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR;
				case COMPUTE -> VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR;
				case COLOR -> VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR;
				case DEPTH -> DEPTH;
				case TRANSFER -> VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR;
				case VERTEX_INPUT -> VK_PIPELINE_STAGE_2_VERTEX_INPUT_BIT_KHR;
				case INDIRECT -> VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT_KHR;
				case ALL -> VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR;
			};
		}
		return stages;
	}

	private static long accesses(int mask, boolean write) {
		long access = 0;
		for (var usage : RenderGraph.Usage.values()) {
			if ((mask & usage.bit) == 0) continue;
			access |= switch (usage) {
				case SHADER -> write ? VK_ACCESS_2_SHADER_WRITE_BIT_KHR : VK_ACCESS_2_SHADER_READ_BIT_KHR;
				case UNIFORM -> VK_ACCESS_2_UNIFORM_READ_BIT_KHR;
				case VERTEX -> VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT_KHR;
				case INDEX -> VK_ACCESS_2_INDEX_READ_BIT_KHR;
				case INDIRECT -> VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT_KHR;
				case COLOR_ATTACHMENT -> write ? VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR : VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT_KHR;
				case DEPTH_ATTACHMENT -> write ? VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR : VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT_KHR;
				case TRANSFER -> write ? VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR : VK_ACCESS_2_TRANSFER_READ_BIT_KHR;
				case MEMORY -> write ? VK_ACCESS_2_MEMORY_WRITE_BIT_KHR : VK_ACCESS_2_MEMORY_READ_BIT_KHR;
			};
		}
		return access;
	}

	public record Dependency(long srcStages, long srcAccess, long dstStages, long dstAccess) implements Runnable {
		public void emit(VkCommandBuffer command, MemoryStack stack) {
			if (srcStages == 0 || dstStages == 0) return;
			var memory = VkMemoryBarrier2.calloc(1, stack).sType$Default().srcStageMask(srcStages).srcAccessMask(srcAccess)
				.dstStageMask(dstStages).dstAccessMask(dstAccess);
			vkCmdPipelineBarrier2KHR(command, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(memory));
		}

		@Override
		public void run() {
			// Deferred native clears must precede the dependency that makes their results visible.
			((VulkanRenderBackend) Iris.getRenderBackend()).textures.flush();
			try (var stack = MemoryStack.stackPush()) {
				this.emit(VulkanCommandBridge.commandBuffer(), stack);
			}
		}
	}
}
