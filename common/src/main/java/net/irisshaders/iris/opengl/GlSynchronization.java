package net.irisshaders.iris.opengl;

import net.irisshaders.iris.backend.RenderGraph;
import net.irisshaders.iris.backend.RenderGraph.Usage;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

public class GlSynchronization {
	public static final Runnable WORLD_IN = () -> IrisRenderSystem.memoryBarrier(GL42C.GL_ALL_BARRIER_BITS);

	public static Runnable compile(RenderGraph.Dependency dependency) {
		if (dependency.srcStages() == 0 || dependency.dstStages() == 0) return RenderGraph.NO_OP;
		// Ordinary framebuffer/transfer writes are coherent in GL. Shader writes need explicit visibility,
		// and subsequent shader stores must also wait for earlier reads.
		if ((dependency.srcWrites() & (Usage.SHADER.bit | Usage.MEMORY.bit)) == 0 && (dependency.dstWrites() & Usage.SHADER.bit) == 0) {
			return RenderGraph.NO_OP;
		}
		int usages = dependency.dstReads() | dependency.dstWrites();
		int barriers = 0;
		for (var usage : Usage.values()) {
			if ((usages & usage.bit) == 0) continue;
			barriers |= switch (usage) {
				case SHADER -> GL42C.GL_TEXTURE_FETCH_BARRIER_BIT | GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
				case UNIFORM -> GL42C.GL_UNIFORM_BARRIER_BIT;
				case VERTEX -> GL42C.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT;
				case INDEX -> GL42C.GL_ELEMENT_ARRAY_BARRIER_BIT;
				case INDIRECT -> GL42C.GL_COMMAND_BARRIER_BIT;
				case COLOR_ATTACHMENT, DEPTH_ATTACHMENT -> GL42C.GL_FRAMEBUFFER_BARRIER_BIT;
				case TRANSFER -> GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT | GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
				case MEMORY -> GL42C.GL_ALL_BARRIER_BITS;
			};
		}
		int bits = barriers;
		return bits == 0 ? RenderGraph.NO_OP : () -> IrisRenderSystem.memoryBarrier(bits);
	}
}
