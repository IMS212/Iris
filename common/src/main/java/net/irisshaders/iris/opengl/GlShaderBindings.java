package net.irisshaders.iris.opengl;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import it.unimi.dsi.fastutil.objects.Object2IntAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.samplers.ProgramResources;
import net.irisshaders.iris.uniforms.buffer.DrawUniforms;
import net.irisshaders.iris.uniforms.buffer.FrameUniforms;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GlShaderBindings implements AutoCloseable {
	private static final Set<ValueUpdateNotifier> notifiers = new HashSet<>();
	private static GlShaderBindings active;
	public final int program;
	public final PatchedShader source;
	private final Object2IntMap<String> bindings;
	private final boolean owned;
	private final String name;
	private final Object2IntMap<PatchShaderType> shaders = new Object2IntAVLTreeMap<>();
	private boolean ready;
	private final GlLooseUniforms looseUniforms;
	private FrameUniforms frameUniforms;
	private int frameRevision;
	private int references = 1;

	public GlShaderBindings(String name, PatchedShader input) {
		this.owned = true;
		this.name = name;
		this.source = input;
		this.bindings = GlShaderPatcher.getBindings(input.layout());
		var sources = this.source.sources();
		this.program = GlStateManager.glCreateProgram();
		this.looseUniforms = new GlLooseUniforms(this.program);

		try {
			for (var entry : sources.entrySet()) {
				int type = switch (entry.getKey()) {
					case VERTEX -> GL20C.GL_VERTEX_SHADER;
					case FRAGMENT -> GL20C.GL_FRAGMENT_SHADER;
					case GEOMETRY -> GL32C.GL_GEOMETRY_SHADER;
					case TESS_CONTROL -> GL40C.GL_TESS_CONTROL_SHADER;
					case TESS_EVAL -> GL40C.GL_TESS_EVALUATION_SHADER;
					case COMPUTE -> GL43C.GL_COMPUTE_SHADER;
				};
				int shader = GlStateManager.glCreateShader(type);
				this.shaders.put(entry.getKey(), shader);
				GlStateManager.glShaderSource(shader, entry.getValue());
				GlStateManager.glCompileShader(shader);

				GlStateManager.glAttachShader(this.program, shader);
			}

			GlStateManager.glLinkProgram(this.program);
		} catch (RuntimeException | Error failure) {
			this.close();
			throw failure;
		}
	}

	public void finishCompilation() {
		if (this.ready) {
			return;
		}

		try {
			if (GlStateManager.glGetProgrami(this.program, GL20C.GL_LINK_STATUS) == 0) {
				for (var entry : this.shaders.object2IntEntrySet()) {
					if (GlStateManager.glGetShaderi(entry.getIntValue(), GL20C.GL_COMPILE_STATUS) == 0) {
						throw new IllegalStateException("failed to compile " + this.name + entry.getKey().extension + ": "
							+ IrisRenderSystem.getShaderInfoLog(entry.getIntValue()));
					}
				}
				throw new IllegalStateException("failed to link " + this.name + ": " + IrisRenderSystem.getProgramInfoLog(this.program));
			}

			this.assignBindings();
			this.ready = true;
		} finally {
			this.deleteShaders();
		}
	}

	private void deleteShaders() {
		this.shaders.values().forEach(GlStateManager::glDeleteShader);
		this.shaders.clear();
	}

	public GlShaderBindings(int program, ShaderResourceLayout layout, Object2IntMap<String> bindings) {
		this.owned = false;
		this.name = "external program " + program;
		this.ready = true;
		this.program = program;
		this.source = new PatchedShader(Map.of(), layout, null, null);
		this.bindings = Object2IntMaps.unmodifiable(new Object2IntOpenHashMap<>(bindings));
		this.looseUniforms = new GlLooseUniforms(this.program);
		this.assignBindings();
	}

	public static void bindTexture(int target, int id) {
		if (target == GL11C.GL_TEXTURE_2D) {
			GlStateManager._bindTexture(id);
		} else {
			GL11C.glBindTexture(target, id);
		}
	}

	private void assignBindings() {
		int previous = GlStateManager._getInteger(GL20C.GL_CURRENT_PROGRAM);
		GlStateManager._glUseProgram(this.program);

		try {
			for (var descriptor : this.source.layout().descriptors()) {
				int binding = this.bindings.getInt(descriptor.name());

				for (int i = 0; i < descriptor.count(); i++) {
					String name = descriptor.count() == 1 ? descriptor.name() : descriptor.name() + "[" + i + "]";

					switch (descriptor.kind()) {
						case UNIFORM_BUFFER -> {
							int index = IrisRenderSystem.getUniformBlockIndex(this.program, name);

							if (index == GL31C.GL_INVALID_INDEX && descriptor.count() == 1) {
								index = IrisRenderSystem.getUniformBlockIndex(this.program, name + "[0]");
							}

							if (index != GL31C.GL_INVALID_INDEX) {
								IrisRenderSystem.uniformBlockBinding(this.program, index, binding + i);
							}
						}
						case STORAGE_BUFFER -> {
							int index = GL43C.glGetProgramResourceIndex(this.program, GL43C.GL_SHADER_STORAGE_BLOCK, name);

							if (index == GL31C.GL_INVALID_INDEX && descriptor.count() == 1) {
								index = GL43C.glGetProgramResourceIndex(this.program, GL43C.GL_SHADER_STORAGE_BLOCK, name + "[0]");
							}

							if (index != GL31C.GL_INVALID_INDEX) {
								GL43C.glShaderStorageBlockBinding(this.program, index, binding + i);
							}
						}
						case SAMPLER, IMAGE ->
							GlStateManager._glUniform1i(GlStateManager._glGetUniformLocation(this.program, name), binding + i);
					}
				}
			}
		} finally {
			GlStateManager._glUseProgram(previous);
		}
	}

	public static void clearDynamicSamplers() {
		for (var notifier : notifiers) {
			notifier.setListener(null);
		}
		notifiers.clear();
		active = null;
	}

	private static void bindGlTexture(ProgramResources.GlTextureBinding texture, int unit) {
		var sampler = texture.sampler() == null ? null : texture.sampler().get();
		IrisRenderSystem.bindSamplerToUnit(unit, sampler == null ? 0 : sampler.getId());
		IrisRenderSystem.bindTextureToUnit(texture.type().getGlType(), unit, texture.texture().getAsInt());
	}

	private void updateGlTextures(ProgramResources resources) {
		for (var descriptor : this.source.layout().descriptors()) {
			if (descriptor.kind() == ShaderResourceLayout.Kind.SAMPLER) {
				var texture = resources.getGlTextureBinding(resources.getSamplerReferences().get(descriptor.name()));
				if (texture != null) {
					bindGlTexture(texture, this.bindings.getInt(descriptor.name()));
				}
			}
		}
	}

	public void bind(ProgramResources resources, DrawUniforms uniforms, ProgramResources.Bindings resolved) {
		this.finishCompilation();
		clearDynamicSamplers();
		active = this;
		GlStateManager._glUseProgram(this.program);
		var buffers = resolved.buffers();

		var frame = uniforms.frameUniforms();
		if (frame != null && (this.frameUniforms != frame || this.frameRevision != frame.revision())) {
			// Program-local values override the same names in the shared frame layout.
			this.looseUniforms.upload(frame.getLayout().uniforms(), frame.data(), this.source.layout().uniforms().keySet());
			this.frameUniforms = frame;
			this.frameRevision = frame.revision();
		}
		this.looseUniforms.upload(this.source.layout().uniforms(), uniforms.data());

		var nativePack = (GlRenderBackend.GlPackResources) resources.getPackSamplers().getResources();

		for (var descriptor : this.source.layout().descriptors()) {
			int binding = this.bindings.getInt(descriptor.name());

			switch (descriptor.kind()) {
				case UNIFORM_BUFFER, STORAGE_BUFFER -> {
					var slices = buffers.get(descriptor.name());

					if (slices == null && descriptor.name().equals("iris_SodiumPushConstants")) {
						continue;
					}

					if (slices == null) {
						throw new IllegalStateException("missing block " + descriptor.name());
					}

					for (int i = 0; i < slices.size(); i++) {
						var slice = slices.get(i);
						GL30C.glBindBufferRange(
							descriptor.kind() == ShaderResourceLayout.Kind.UNIFORM_BUFFER ? GL31C.GL_UNIFORM_BUFFER : GL43C.GL_SHADER_STORAGE_BUFFER,
							binding + i, ((GlBuffer) slice.buffer()).handle(), slice.offset(), slice.length());
					}
				}

				case SAMPLER -> {
					var reference = resources.getSamplerReferences().get(descriptor.name());
					var texture = resolved.textures().get(reference);
					var glTexture = resources.getGlTextureBinding(reference);
					if (glTexture != null) {
						bindGlTexture(glTexture, binding);
						if (glTexture.notifier() != null && notifiers.add(glTexture.notifier())) {
							glTexture.notifier().setListener(() -> this.updateGlTextures(resources));
						}
						continue;
					}

					GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + binding);
					if (texture == null) {
						GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, Math.toIntExact(resolved.texels().getLong(reference)));
						IrisRenderSystem.bindSamplerToUnit(binding, 0);
						continue;
					}

					int target;
					int id;

					if (texture.image() != null) {
						var image = texture.image();
						target = image.getType().getGlType();
						id = Math.toIntExact(image.getView());
					} else {
						var view = (GlTextureView) texture.texture();

						target = (view.texture().usage() & 16) != 0 ? GL13C.GL_TEXTURE_CUBE_MAP : GL11C.GL_TEXTURE_2D;
						id = view.glId();
					}

					bindTexture(target, id);

					if (texture.image() == null) {
						var view = texture.texture();
						GlStateManager._texParameter(target, GL12C.GL_TEXTURE_BASE_LEVEL, view.baseMipLevel());
						GlStateManager._texParameter(target, GL12C.GL_TEXTURE_MAX_LEVEL, view.baseMipLevel() + view.mipLevels() - 1);
					}
					var external = texture.nativeSampler();
					int sampler = external == null ? nativePack.getSampler(texture.sampler()) : ((GlSampler) external).getId();
					IrisRenderSystem.bindSamplerToUnit(binding, sampler);
				}

				case IMAGE -> {
					var resource = resolved.images().get(descriptor.name()).getFirst();
					int image = resource.image() == null ? ((GlTextureView) resource.texture()).glId() : Math.toIntExact(
						resource.image().getView());
					int format = resources.getImageFormats().get(descriptor.name()).getGlFormat();
					int access = switch (descriptor.access()) {
						case READ_ONLY -> GL15C.GL_READ_ONLY;
						case WRITE_ONLY -> GL15C.GL_WRITE_ONLY;
						case READ_WRITE -> GL15C.GL_READ_WRITE;
					};
					IrisRenderSystem.bindImageTexture(binding, image, 0, descriptor.type().contains("3D"), 0, access, format);
				}
			}
		}
	}

	public void bindPushConstants(GpuBufferSlice data) {
		int binding = this.bindings.getInt("iris_SodiumPushConstants");

		if (this.bindings.containsKey("iris_SodiumPushConstants")) {
			GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, binding, ((GlBuffer) data.buffer()).handle(), data.offset(), data.length());
		}
	}

	public boolean hasStorageWrites(ProgramResources resources) {
		return resources.getStorageDeclarations().stream().anyMatch(descriptor -> descriptor.access().writes())
			|| resources.getImageDeclarations().values().stream().anyMatch(descriptor -> descriptor.access().writes());
	}

	public GlShaderBindings retain() {
		if (this.references == 0) {
			throw new IllegalStateException("shader closed");
		}

		this.references++;
		return this;
	}

	@Override
	public void close() {
		if (this.references > 0 && --this.references == 0) {
			this.frameUniforms = null;
			if (active == this) {
				clearDynamicSamplers();
			}
			if (this.owned) {
				this.deleteShaders();
				GlStateManager.glDeleteProgram(this.program);
			}
		}
	}
}
