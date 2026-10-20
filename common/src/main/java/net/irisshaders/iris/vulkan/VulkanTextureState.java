package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class VulkanTextureState {
	private final Long2ObjectLinkedOpenHashMap<Clear> clears = new Long2ObjectLinkedOpenHashMap<>();
	private final VulkanRenderBackend backend;

	public VulkanTextureState(VulkanRenderBackend backend) {
		this.backend = backend;
	}

	public void clear(List<GpuTextureView> views, Vector4f color) {
		if (views.isEmpty()) {
			return;
		}

		var value = new Vector4f(color);

		for (var view : views) {
			if (view.baseMipLevel() == 0 && view.mipLevels() == 1 && view.texture().getDepthOrLayers() == 1 && (view.texture()
				.usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
				this.clears.put(((VulkanGpuTexture) view.texture()).vkImage(), new Clear(view, value));
			} else {
				this.flush();
				VulkanTextureOps.clearColors(List.of(view), color);
			}
		}
	}

	public RenderPassDescriptor takeClears(RenderPassDescriptor descriptor) {
		if (this.clears.isEmpty()) {
			return descriptor;
		}
		var area = descriptor.renderArea();
		for (var attachment : descriptor.colorAttachments()) {
			if (attachment == null) {
				continue;
			}
			var view = attachment.textureView();
			if (this.clears.containsKey(((VulkanGpuTexture) view.texture()).vkImage()) && (view.baseMipLevel() != 0 || view.mipLevels() != 1 || area.x() != 0 || area.y() != 0 || area.width() != view.getWidth(0) || area.height() != view.getHeight(0))) {
				this.flush();
				return descriptor;
			}
		}
		var attachments = new ArrayList<RenderPassDescriptor.Attachment<Optional<Vector4fc>>>();
		boolean changed = false;
		for (var attachment : descriptor.colorAttachments()) {
			if (attachment == null) {
				attachments.add(null);
				continue;
			}

			var view = attachment.textureView();
			if (view.baseMipLevel() == 0 && view.mipLevels() == 1 && area.x() == 0 && area.y() == 0 && area.width() == view.getWidth(
				0) && area.height() == view.getHeight(0)) {
				var clear = this.clears.remove(((VulkanGpuTexture) view.texture()).vkImage());
				if (clear != null && attachment.clearValue().isEmpty()) {
					attachment = new RenderPassDescriptor.Attachment<>(view, Optional.of(clear.color()));
					changed = true;
				}
			}
			attachments.add(attachment);
		}

		return changed ? new RenderPassDescriptor(descriptor.label(), attachments, descriptor.depthAttachment(),
			descriptor.renderArea()) : descriptor;
	}

	public void beforeAccess(GpuTexture texture) {
		if (this.clears.containsKey(((VulkanGpuTexture) texture).vkImage())) {
			this.flush();
		}
	}

	public void release(GpuTexture texture) {
		long image = ((VulkanGpuTexture) texture).vkImage();
		this.clears.remove(image);
	}

	public void flush() {
		if (this.clears.isEmpty()) {
			return;
		}

		var groups = new Long2ObjectLinkedOpenHashMap<List<Clear>>();
		for (var clear : this.clears.values()) {
			var view = clear.view();
			long size = (long) view.getWidth(0) << 32 | Integer.toUnsignedLong(view.getHeight(0));
			groups.computeIfAbsent(size, ignored -> new ArrayList<>()).add(clear);
		}
		this.clears.clear();

		var command = VulkanCommandBridge.commandBuffer();
		int limit = VulkanCommandBridge.getDevice().getDeviceInfo().limits().maxColorAttachments();
		try (var stack = MemoryStack.stackPush()) {
			VulkanSynchronization.CLEAR_IN.emit(command, stack);
			for (var group : groups.values()) {
				for (int first = 0; first < group.size(); first += limit) {
					var builder = RenderPassDescriptor.builder(() -> "Iris pending clears");
					for (int i = first; i < Math.min(first + limit, group.size()); i++) {
						var clear = group.get(i);
						builder.withColorAttachment(clear.view(), Optional.of(clear.color()));
					}
					this.backend.getRenderPassCache().begin(command, stack, builder.build());
					VK10.vkCmdEndRenderPass(command);
				}
			}
			VulkanSynchronization.CLEAR_OUT.emit(command, stack);
		}
	}

	public record Clear(GpuTextureView view, Vector4fc color) {
	}
}
