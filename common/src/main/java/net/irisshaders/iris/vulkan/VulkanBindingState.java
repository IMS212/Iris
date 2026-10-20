package net.irisshaders.iris.vulkan;

// this exists to help the drivers that do not manage to track this themselves... like NVIDIA! Thanks.
public class VulkanBindingState {
	private long set;
	private long pipeline;
	private long layout;

	public void reset() {
		this.pipeline = 0;
		this.layout = 0;
		this.set = 0;
	}

	public boolean pipeline(long pipeline) {
		if (this.pipeline == pipeline) return false;
		this.pipeline = pipeline;
		return true;
	}

	public boolean descriptor(long layout, long set) {
		if (this.layout == layout && this.set == set) return false;
		this.layout = layout;
		this.set = set;
		return true;
	}
}
