package net.irisshaders.iris.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.programs.CompositeProgram;
import net.irisshaders.iris.pipeline.programs.ComputeProgram;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.samplers.PackSamplers;
import net.irisshaders.iris.targets.RenderTarget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Build-time dependency compiler for one dimension's ordered pack passes, shared by both backends. */
public class RenderGraph {
	public static final Runnable NO_OP = () -> { };
	private final RenderBackend backend = Iris.getRenderBackend();
	private final PackSamplers pack;
	private final Map<Resource, State> states = new HashMap<>();
	private final Set<Resource> mipmaps = new HashSet<>();
	public boolean optional;

	public RenderGraph(PackSamplers pack) {
		this.pack = pack;
	}

	public static Resource image(GpuTexture texture) {
		return new Resource(true, Iris.getRenderBackend().textureId(texture));
	}

	public static Resource buffer(GpuBuffer buffer) {
		return new Resource(false, Iris.getRenderBackend().bufferId(buffer));
	}

	public static int stages(Set<PatchShaderType> stages) {
		int mask = 0;
		for (var stage : stages) mask |= Stage.valueOf(stage.name()).bit;
		return mask;
	}

	public void compute(ComputeProgram program) {
		var accesses = program.getSamplers().graphAccesses();
		var indirect = program.indirect();
		if (indirect != null) accesses.add(new Access(buffer(indirect.buffer()), Stage.INDIRECT.bit, Usage.INDIRECT.bit, 0));
		program.before = this.add(accesses);
	}

	public void graphics(CompositeProgram program, List<GpuTextureView> targets) {
		this.graphics(program, program.getSamplers().graphAccesses(), targets);
	}

	public void graphics(CompositeProgram program, List<Access> reads, List<GpuTextureView> targets) {
		var accesses = new ArrayList<>(reads);
		for (var target : targets) {
			accesses.add(new Access(image(target.texture()), Stage.COLOR.bit, Usage.COLOR_ATTACHMENT.bit, Usage.COLOR_ATTACHMENT.bit));
		}
		program.before = this.add(accesses);
	}

	public Runnable mip(RenderTarget target, boolean alt) {
		if (target == null) return NO_OP;
		var texture = target.getTextureView(alt, false).texture();
		var resource = image(texture);
		if (this.mipmaps.contains(resource)) return () -> target.turnOnMips(alt);
		var before = this.add(this.backend.mipmapAccesses(texture, target.allowsLinearFiltering(), this.pack));
		if (!this.optional) this.mipmaps.add(resource);
		return () -> {
			before.run();
			target.generateMipmaps(alt);
		};
	}

	public Runnable depthMip(GpuTexture texture) {
		var before = this.add(this.backend.depthMipmapAccesses(texture));
		return () -> {
			before.run();
			Iris.getRenderBackend().commands().generateDepthMips(texture);
		};
	}

	public Runnable copy(GpuTexture source, GpuTexture target) {
		return this.add(List.of(
			new Access(image(source), Stage.TRANSFER.bit, Usage.TRANSFER.bit, 0),
			new Access(image(target), Stage.TRANSFER.bit, 0, Usage.TRANSFER.bit)));
	}

	/** Export pack writes to Minecraft, then import its potentially changing draw sequence conservatively. */
	public Runnable external() {
		int stages = 0, writes = 0;
		for (var state : this.states.values()) {
			stages |= state.writerStages | state.readerStages;
			writes |= state.writerWrites;
		}
		this.states.clear();
		this.mipmaps.clear();
		return this.backend.barrier(new Dependency(stages, writes, Stage.ALL.bit, Usage.MEMORY.bit, Usage.MEMORY.bit));
	}

	public Runnable add(List<Access> accesses) {
		var merged = new HashMap<Resource, Access>();
		for (var access : accesses) {
			merged.merge(access.resource(), access,
				(a, b) -> new Access(a.resource(), a.stages() | b.stages(), a.reads() | b.reads(), a.writes() | b.writes()));
		}
		for (var resource : merged.keySet()) this.states.computeIfAbsent(resource, ignored -> new State());
		int srcStages = 0, srcWrites = 0, dstStages = 0, dstReads = 0, dstWrites = 0;
		for (var access : merged.values()) {
			for (var entry : this.states.entrySet()) {
				var resource = entry.getKey();
				if (resource.image() != access.resource().image() || resource.handle() != 0 && access.resource().handle() != 0
					&& !resource.equals(access.resource())) continue;
				var state = entry.getValue();
				if (access.writes() != 0) {
					srcStages |= state.writerStages | state.readerStages;
					srcWrites |= state.writerWrites;
					dstStages |= access.stages();
					dstReads |= access.reads();
					dstWrites |= access.writes();
				} else if ((state.visibleStages & access.stages()) != access.stages() || (state.visibleReads & access.reads()) != access.reads()) {
					srcStages |= state.writerStages;
					srcWrites |= state.writerWrites;
					dstStages |= access.stages();
					dstReads |= access.reads();
				}
			}
		}
		for (var access : merged.values()) {
			var state = this.states.get(access.resource());
			if (access.writes() != 0) {
				if (!this.optional) state.writerStages = state.writerWrites = state.readerStages = 0;
				state.writerStages |= access.stages();
				state.writerWrites |= access.writes();
				state.visibleStages = state.visibleReads = 0;
				this.mipmaps.remove(access.resource());
			} else {
				state.readerStages |= access.stages();
				if (!this.optional) {
					// Keep a complete stage/access scope; separate unions can invent unsynchronized pairs.
					state.visibleStages = access.stages();
					state.visibleReads = access.reads();
				}
			}
		}
		return this.backend.barrier(new Dependency(srcStages, srcWrites, dstStages, dstReads, dstWrites));
	}

	public enum Stage {
		VERTEX, TESS_CONTROL, TESS_EVAL, GEOMETRY, FRAGMENT, COMPUTE, COLOR, DEPTH, TRANSFER, VERTEX_INPUT, INDIRECT, ALL;
		public final int bit = 1 << this.ordinal();
	}

	public enum Usage {
		SHADER, UNIFORM, VERTEX, INDEX, INDIRECT, COLOR_ATTACHMENT, DEPTH_ATTACHMENT, TRANSFER, MEMORY;
		public final int bit = 1 << this.ordinal();
	}

	public record Resource(boolean image, long handle) { }
	public record Access(Resource resource, int stages, int reads, int writes) { }
	public record Dependency(int srcStages, int srcWrites, int dstStages, int dstReads, int dstWrites) { }

	public static class State {
		// Frame history and native uploads can precede the first use of any resource.
		private int writerStages = Stage.ALL.bit;
		private int writerWrites = Usage.MEMORY.bit;
		private int readerStages;
		private int visibleStages;
		private int visibleReads;
	}
}
